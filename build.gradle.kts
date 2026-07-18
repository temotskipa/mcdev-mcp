import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.WriteProperties
import org.gradle.api.tasks.compile.JavaCompile
import java.nio.charset.StandardCharsets
import java.nio.file.Files

abstract class McpSdkSnapshotCheck : DefaultTask() {
    @get:Input
    abstract val resolvedModules: MapProperty<String, String>

    @TaskAction
    fun verifyRuntimeClasspath() {
        val gsonModuleName = String(charArrayOf('g', 's', 'o', 'n'))
        val expectedVersions = mapOf(
            "io.modelcontextprotocol.sdk:mcp" to "2.0.1-SNAPSHOT",
            "io.modelcontextprotocol.sdk:mcp-core" to "2.0.1-SNAPSHOT",
            "io.modelcontextprotocol.sdk:mcp-json-jackson3" to "2.0.1-SNAPSHOT",
            "tools.jackson.core:jackson-core" to "3.1.4",
            "tools.jackson.core:jackson-databind" to "3.1.4",
            "com.networknt:json-schema-validator" to "3.0.6"
        )
        val runtimeModules = resolvedModules.get()
        val mismatches = expectedVersions.mapNotNull { (module, expectedVersion) ->
            val actualVersion = runtimeModules[module]
            if (actualVersion == expectedVersion) {
                null
            } else {
                "$module resolved $actualVersion, expected $expectedVersion"
            }
        }
        val gsonModules = runtimeModules.filter { (module, _) ->
            module.substringBefore(':').equals("com.google.code.$gsonModuleName", ignoreCase = true) ||
                module.substringAfter(':').equals(gsonModuleName, ignoreCase = true)
        }

        check(mismatches.isEmpty() && gsonModules.isEmpty()) {
            buildString {
                appendLine("MCP SDK snapshot dependency verification failed.")
                mismatches.forEach(::appendLine)
                gsonModules.forEach { (module, version) ->
                    appendLine("Unexpected ${gsonModuleName.replaceFirstChar { it.uppercase() }} module: $module:$version")
                }
            }
        }
    }
}

plugins {
    application
    id("com.gradleup.shadow") version libs.versions.shadow.get()
}

val applicationVersion = providers.gradleProperty("version").get()
val testJavaFeature = providers.gradleProperty("testJavaVersion").orElse("25").map { configuredVersion ->
    configuredVersion.toInt().also { feature ->
        require(feature == 25 || feature == 26) { "testJavaVersion must be 25 or 26, got $feature" }
    }
}
val testJavaLauncher = javaToolchains.launcherFor {
    languageVersion.set(testJavaFeature.map(JavaLanguageVersion::of))
}

val generateTestVersionProperties = tasks.register<WriteProperties>("generateTestVersionProperties") {
    description = "Generates version metadata for Java tests."
    destinationFile = layout.buildDirectory.file("generated-test-resources/version.properties").get().asFile
    property("version", applicationVersion)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    implementation(project(":mcp-tool-binding"))
    implementation(libs.mcp)
    implementation(libs.picocli)
    implementation(libs.h2)
    implementation(libs.vineflower)
    implementation(libs.tiny.remapper)
    implementation(libs.slf4j.nop)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("dev.mcdevmcp.app.Main")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

sourceSets {
    test {
        resources.srcDir(layout.buildDirectory.dir("generated-test-resources"))
    }
}

tasks.processTestResources {
    dependsOn(generateTestVersionProperties)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    dependsOn(tasks.named("shadowJar"))
    javaLauncher.set(testJavaLauncher)
    testLogging.showStandardStreams = true
    systemProperty("dev.mcdevmcp.test.versionFallback", "true")
    systemProperty("dev.mcdevmcp.test.javaFeature", testJavaFeature.get())
    systemProperty("mcdevMcpVersion", applicationVersion)
    systemProperty("mcdevMcpJar", layout.buildDirectory.file("libs/mcdev-mcp-$applicationVersion.jar").get().asFile.absolutePath)
    systemProperty("mcdevMcpJava", testJavaLauncher.get().executablePath.asFile.absolutePath)
}

tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("mcdev-mcp")
    archiveClassifier.set("")
    archiveVersion.set(applicationVersion)
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    append("META-INF/LICENSE")
    append("META-INF/LICENSE.txt")
    append("META-INF/NOTICE")
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    manifest {
        attributes[
            "Main-Class"
        ] = application.mainClass.get()
        attributes["Implementation-Version"] = applicationVersion
    }
}

tasks.named("build") {
    dependsOn(tasks.named("shadowJar"))
}

val runtimeModuleVersions = configurations.named("runtimeClasspath").map { configuration ->
    configuration.incoming.resolutionResult.allComponents
        .mapNotNull { component -> component.moduleVersion }
        .associate { module -> "${module.group}:${module.name}" to module.version }
}

val mcpSdkSnapshotCheck = tasks.register<McpSdkSnapshotCheck>("mcpSdkSnapshotCheck") {
    group = "verification"
    description = "Verifies the reviewed MCP SDK snapshot runtime dependencies."
    resolvedModules.set(runtimeModuleVersions)
}

val cutoverCheck = tasks.register("cutoverCheck") {
    group = "verification"
    description = "Rejects tracked retired implementation surface."
    val repositoryRoot = project.layout.projectDirectory.asFile
    val allowedScriptFiles = setOf("packaging/mcpb/bootstrap.cjs")
    val allowedPackageMetadata = setOf(
        "packaging/mcpb/package.json",
        "packaging/mcpb/package-lock.json"
    )
    val forbiddenMetadataFiles = setOf(
        "tsconfig.json",
        "jest.config.js",
        "eslint.config.js"
    )
    val forbiddenReferences = Regex(
        listOf(
            "\\btype" + "script\\b",
            "\\bts-" + "jest\\b",
            "\\bb" + "un\\b",
            "@modelcontextprotocol/" + "sdk",
            "\\bjava-" + "parser\\b",
            "\\bsql" + "\\.js\\b",
            "\\bgso" + "n\\b",
            "\\bjson" + "node\\b",
            "\\bjava-" + "callgraph" + "2\\b",
            "\\bjava" + "cg(?:-static)?(?:\\.jar)?\\b",
            "\\bcallgraph" + "\\.txt\\b",
            "\\b(?:build|copy)-java-worker\\b",
            "\\bMCDEV_AST_" + "PARSER\\b",
            "\\bMCDEV_" + "INDEXER\\b",
            "\\bMCDEV_SUPPRESS_INDEXER_" + "HINT\\b",
            "\\bMCDEV_JAVA_WORKER_" + "COMMAND\\b",
            "\\bMCDEV_JAVA_WORKER_ARGS_" + "JSON\\b",
            "\\bMCDEV_INDEX_" + "WORKERS\\b",
            "\\bMCDEV_INDEX_BATCH_" + "SIZE\\b",
            "\\bMCDEV_INDEX_WORKER_HEAP_" + "MB\\b",
            "\\bMCDEV_INDEX_WORKER_RETRY_HEAP_" + "MB\\b",
            "\\bMCDEV_INDEX_PARSE_WORKER_" + "PATH\\b",
            "\\bMCDEV_INDEX_WORKER_" + "MARKER\\b",
            "\\bMCDEV_INDEX_SINGLE_FILE_" + "FALLBACK\\b",
            "\\bMCDEV_MCP_REMAPPER_" + "HEAP\\b",
            "\\bMCDEV_ARGV_" + "CAPTURE\\b",
            "\\bpackage[- ]json (?:index|reader|writer)s?\\b"
        ).joinToString("|"),
        RegexOption.IGNORE_CASE
    )
    doLast {
        val git = ProcessBuilder("git", "ls-files", "-z")
            .directory(repositoryRoot)
            .redirectErrorStream(true)
            .start()
        val trackedOutput = git.inputStream.readBytes()
        check(git.waitFor() == 0) {
            "Unable to list tracked files for cutoverCheck: ${trackedOutput.toString(StandardCharsets.UTF_8)}"
        }

        val trackedFiles = trackedOutput.toString(StandardCharsets.UTF_8)
            .split('\u0000')
            .filter(String::isNotEmpty)
        val violations = mutableListOf<String>()

        trackedFiles.forEach { path ->
            val normalizedPath = path.replace('\\', '/')
            val lowercasePath = normalizedPath.lowercase()
            val fileName = lowercasePath.substringAfterLast('/')
            if (
                lowercasePath.endsWith(".ts") ||
                lowercasePath.endsWith(".tsx") ||
                ((lowercasePath.endsWith(".js") ||
                    lowercasePath.endsWith(".mjs") ||
                    lowercasePath.endsWith(".cjs")) &&
                    normalizedPath !in allowedScriptFiles) ||
                ((fileName == "package.json" || fileName == "package-lock.json") &&
                    normalizedPath !in allowedPackageMetadata) ||
                fileName in forbiddenMetadataFiles ||
                lowercasePath.startsWith("java-worker/")
            ) {
                violations += "forbidden tracked file: $normalizedPath"
            }

            val mustInspectContents =
                (lowercasePath.endsWith(".json") &&
                    normalizedPath != "contracts/node-oracle.json" &&
                    !normalizedPath.startsWith("src/test/resources/contracts/") &&
                    !normalizedPath.startsWith("src/test/resources/oracle/") &&
                    !normalizedPath.startsWith("docs/superpowers/")) ||
                normalizedPath == "build.gradle.kts" ||
                normalizedPath == "settings.gradle.kts" ||
                normalizedPath.endsWith(".gradle") ||
                normalizedPath.endsWith(".gradle.kts") ||
                normalizedPath.endsWith(".toml") ||
                normalizedPath.endsWith(".properties") ||
                normalizedPath.startsWith(".github/") ||
                normalizedPath.startsWith("scripts/") ||
                (normalizedPath.startsWith("src/main/") &&
                    !normalizedPath.startsWith("src/main/resources/"))
            if (mustInspectContents) {
                val file = repositoryRoot.toPath().resolve(path)
                if (Files.isRegularFile(file)) {
                    val contents = Files.readString(file)
                    val forbiddenNodeMetadata = if (lowercasePath.endsWith(".json")) {
                        val isPackagingMetadata = normalizedPath.startsWith("packaging/mcpb/")
                        val parsedJson = runCatching { JsonSlurper().parseText(contents) }
                            .getOrElse { cause ->
                                violations += "invalid inspected JSON metadata: $normalizedPath (${cause.message})"
                                null
                            }
                        val jsonFields = mutableListOf<Pair<String, String>>()
                        val pendingJsonValues = ArrayDeque<Pair<Any, String?>>()
                        if (parsedJson != null) {
                            pendingJsonValues.add(parsedJson to null)
                        }
                        while (pendingJsonValues.isNotEmpty()) {
                            val (jsonValue, parentKey) = pendingJsonValues.removeFirst()
                            when (jsonValue) {
                                is Map<*, *> -> jsonValue.forEach { (key, nestedValue) ->
                                    if (key is String && nestedValue is String) {
                                        jsonFields += key to nestedValue
                                    }
                                    if (nestedValue != null) {
                                        pendingJsonValues.add(nestedValue to (key as? String ?: parentKey))
                                    }
                                }

                                is Iterable<*> -> jsonValue.forEach { nestedValue ->
                                    if (nestedValue != null) {
                                        pendingJsonValues.add(nestedValue to parentKey)
                                    }
                                }
                            }
                        }
                        fun normalizedMetadataKey(key: String) =
                            key.lowercase().replace("_", "").replace("-", "")

                        fun normalizedEntrypoint(value: String) =
                            value.trim().replace('\\', '/').removePrefix("./")

                        fun hasNodeOptionsMetadata(root: Any): Boolean {
                            val environmentKeys = setOf("env", "environment")
                            val pendingEnvironmentValues = ArrayDeque<Pair<Any, Boolean>>()
                            pendingEnvironmentValues.add(root to false)
                            while (pendingEnvironmentValues.isNotEmpty()) {
                                val (value, assignmentList) = pendingEnvironmentValues.removeFirst()
                                when (value) {
                                    is Map<*, *> -> value.forEach { (key, nestedValue) ->
                                        val normalizedKey = (key as? String)?.let(::normalizedMetadataKey)
                                        if (normalizedKey == "nodeoptions") {
                                            return true
                                        }
                                        if (nestedValue != null) {
                                            pendingEnvironmentValues.add(
                                                nestedValue to
                                                    (normalizedKey in environmentKeys &&
                                                        (nestedValue is String || nestedValue is Iterable<*>))
                                            )
                                        }
                                    }
                                    is Iterable<*> -> value.filterNotNull().forEach { nestedValue ->
                                        pendingEnvironmentValues.add(
                                            nestedValue to
                                                (assignmentList &&
                                                    (nestedValue is String || nestedValue is Iterable<*>))
                                        )
                                    }
                                    is String -> {
                                        val assignment = value.indexOf('=')
                                        if (assignmentList &&
                                            assignment > 0 &&
                                            normalizedMetadataKey(value.substring(0, assignment).trim()) == "nodeoptions"
                                        ) {
                                            return true
                                        }
                                    }
                                }
                            }
                            return false
                        }

                        val allowedEntrypoints = setOf(
                            "bootstrap.cjs",
                            "packaging/mcpb/bootstrap.cjs"
                        )
                        val javascriptEntrypoint = Regex(
                            """(?i)\b[^\s"';|&()]*\.(?:js|mjs|cjs|ts|tsx)\b"""
                        )

                        fun hasForbiddenJavaScriptEntrypoint(value: String) =
                            javascriptEntrypoint.findAll(value).any { match ->
                                !isPackagingMetadata ||
                                        normalizedEntrypoint(match.value) !in allowedEntrypoints
                            }

                        fun hasForbiddenEntrypointTarget(value: String) =
                            if (isPackagingMetadata) {
                                normalizedEntrypoint(value) !in allowedEntrypoints
                            } else {
                                hasForbiddenJavaScriptEntrypoint(value)
                            }

                        fun normalizedExecutableName(value: String) =
                            value.trim().replace('\\', '/').substringAfterLast('/').lowercase().removeSuffix(".exe")

                        fun isNodeCommand(value: String) = normalizedExecutableName(value) == "node"

                        fun parseCommandSegments(value: String): List<List<String>>? {
                            val segments = mutableListOf<List<String>>()
                            val words = mutableListOf<String>()
                            val word = StringBuilder()
                            var wordStarted = false
                            var quote: Char? = null
                            var requiresSegment = false
                            var index = 0

                            fun finishWord() {
                                if (wordStarted) {
                                    words += word.toString()
                                    word.setLength(0)
                                    wordStarted = false
                                }
                            }

                            fun finishSegment(requiresFollowingSegment: Boolean): Boolean {
                                finishWord()
                                if (words.isEmpty()) {
                                    return false
                                }
                                segments += words.toList()
                                words.clear()
                                requiresSegment = requiresFollowingSegment
                                return true
                            }

                            while (index < value.length) {
                                val character = value[index]
                                if (quote != null) {
                                    when (character) {
                                        quote -> quote = null
                                        '\\' -> {
                                            if (index + 1 < value.length && value[index + 1] == quote) {
                                                index++
                                                word.append(value[index])
                                            } else {
                                                word.append(character)
                                            }
                                        }
                                        else -> word.append(character)
                                    }
                                    wordStarted = true
                                    index++
                                    continue
                                }

                                when {
                                    character == '\'' || character == '"' -> {
                                        quote = character
                                        wordStarted = true
                                    }
                                    character == '\r' || character == '\n' || character == ';' -> {
                                        finishWord()
                                        if (words.isNotEmpty()) {
                                            finishSegment(false)
                                        } else if (requiresSegment && character == ';') {
                                            return null
                                        }
                                        if (character == '\r' &&
                                            index + 1 < value.length &&
                                            value[index + 1] == '\n'
                                        ) {
                                            index++
                                        }
                                    }
                                    character == '&' -> {
                                        if (index + 1 >= value.length || value[index + 1] != '&') {
                                            return null
                                        }
                                        if (!finishSegment(true)) {
                                            return null
                                        }
                                        index++
                                    }
                                    character == '|' -> {
                                        if (!finishSegment(true)) {
                                            return null
                                        }
                                        if (index + 1 < value.length && value[index + 1] == '|') {
                                            index++
                                        }
                                    }
                                    character.isWhitespace() -> finishWord()
                                    character == '\\' && index + 1 < value.length &&
                                        (value[index + 1].isWhitespace() ||
                                            value[index + 1] in setOf('\'', '"', ';', '&', '|')) -> {
                                        index++
                                        word.append(value[index])
                                        wordStarted = true
                                    }
                                    else -> {
                                        word.append(character)
                                        wordStarted = true
                                    }
                                }
                                index++
                            }

                            if (quote != null) {
                                return null
                            }
                            finishWord()
                            if (words.isNotEmpty()) {
                                finishSegment(false)
                            } else if (requiresSegment) {
                                return null
                            }
                            return segments
                        }

                        fun resolveCommandSegment(words: List<String>): Pair<Int, Boolean>? {
                            val assignmentName = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
                            var executableIndex = 0
                            var hasNodeOptions = false

                            fun consumeAssignments() {
                                while (executableIndex < words.size) {
                                    val assignment = words[executableIndex].indexOf('=')
                                    if (assignment <= 0) {
                                        break
                                    }
                                    val name = words[executableIndex].substring(0, assignment)
                                    if (!assignmentName.matches(name)) {
                                        break
                                    }
                                    hasNodeOptions = hasNodeOptions ||
                                        normalizedMetadataKey(name) == "nodeoptions"
                                    executableIndex++
                                }
                            }

                            consumeAssignments()
                            while (executableIndex < words.size) {
                                when (normalizedExecutableName(words[executableIndex])) {
                                    "env" -> {
                                        executableIndex++
                                        if (executableIndex < words.size && words[executableIndex] == "--") {
                                            executableIndex++
                                        } else if (executableIndex < words.size &&
                                            words[executableIndex].startsWith("-")
                                        ) {
                                            return null
                                        }
                                        consumeAssignments()
                                    }
                                    "exec", "command" -> {
                                        executableIndex++
                                        if (executableIndex < words.size && words[executableIndex] == "--") {
                                            executableIndex++
                                        } else if (executableIndex < words.size &&
                                            words[executableIndex].startsWith("-")
                                        ) {
                                            return null
                                        }
                                    }
                                    else -> break
                                }
                            }
                            if (executableIndex < words.size && words[executableIndex].contains('=')) {
                                return null
                            }
                            return executableIndex.takeIf { it < words.size }
                                ?.let { it to hasNodeOptions }
                        }

                        fun hasForbiddenNodeCommandText(value: String): Boolean {
                            val nodeToken = Regex("""(?i)\bnode(?:\.exe)?\b""")
                            val segments = parseCommandSegments(value)
                                ?: return nodeToken.containsMatchIn(value)
                            for (segment in segments) {
                                val (executableIndex, hasNodeOptions) = resolveCommandSegment(segment)
                                    ?: if (segment.any(nodeToken::containsMatchIn)) {
                                        return true
                                    } else {
                                        continue
                                    }
                                if (!isNodeCommand(segment[executableIndex])) {
                                    continue
                                }
                                if (hasNodeOptions || !isPackagingMetadata) {
                                    return true
                                }
                                val arguments = segment.drop(executableIndex + 1)
                                if (arguments.isNotEmpty() &&
                                    (normalizedEntrypoint(arguments.first()) !in allowedEntrypoints ||
                                        arguments.drop(1).any(::hasForbiddenJavaScriptEntrypoint))
                                ) {
                                    return true
                                }
                            }
                            return false
                        }

                        fun stringArguments(value: Any?): List<String> = when (value) {
                            is String -> listOf(value)
                            is Map<*, *> -> value.values.flatMap(::stringArguments)
                            is Iterable<*> -> value.flatMap(::stringArguments)
                            else -> emptyList()
                        }

                        fun hasForbiddenStructuredNodeInvocation(root: Any): Boolean {
                            val pendingCommands = ArrayDeque<Any>()
                            pendingCommands.add(root)
                            while (pendingCommands.isNotEmpty()) {
                                when (val value = pendingCommands.removeFirst()) {
                                    is Map<*, *> -> {
                                        val fields = value.entries.associate { (key, fieldValue) ->
                                            (key as? String)?.let(::normalizedMetadataKey) to fieldValue
                                        }
                                        val command = fields["command"]
                                        val explicitArguments = fields["args"] ?: fields["arguments"]
                                        val (executable, arguments) = when (command) {
                                            is String -> command to stringArguments(explicitArguments)
                                            is Iterable<*> -> {
                                                val commandParts = command.toList()
                                                (commandParts.firstOrNull() as? String) to
                                                    (stringArguments(commandParts.drop(1)) +
                                                        stringArguments(explicitArguments))
                                            }
                                            is Map<*, *> -> {
                                                val commandFields = command.entries.associate { (key, fieldValue) ->
                                                    (key as? String)?.let(::normalizedMetadataKey) to fieldValue
                                                }
                                                val commandExecutable =
                                                    listOf("command", "executable", "program")
                                                        .firstNotNullOfOrNull { commandFields[it] as? String }
                                                val commandArguments =
                                                    commandFields["args"] ?: commandFields["arguments"]
                                                commandExecutable to
                                                    (stringArguments(commandArguments) +
                                                        stringArguments(explicitArguments))
                                            }
                                            else -> null to emptyList()
                                        }
                                        if (executable != null && isNodeCommand(executable)) {
                                            if (!isPackagingMetadata ||
                                                arguments.isNotEmpty() &&
                                                (normalizedEntrypoint(arguments.first()) !in allowedEntrypoints ||
                                                    arguments.drop(1).any(::hasForbiddenJavaScriptEntrypoint))
                                            ) {
                                                return true
                                            }
                                        }
                                        value.values.filterNotNull().forEach(pendingCommands::add)
                                    }
                                    is Iterable<*> -> value.filterNotNull().forEach(pendingCommands::add)
                                }
                            }
                            return false
                        }

                        val entrypointKeys = setOf("main", "module", "browser", "bin", "exports", "entrypoint")
                        val nodeRuntime = jsonFields.any { (key, value) ->
                            val normalizedKey = normalizedMetadataKey(key)
                            (normalizedKey == "runtime" || normalizedKey == "type") &&
                                    value.equals("node", ignoreCase = true) ||
                                    normalizedKey == "command" &&
                                    (value.equals("node", ignoreCase = true) ||
                                            value.equals("node.exe", ignoreCase = true))
                        }
                        val javascriptMetadata = jsonFields.any { (key, value) ->
                            val normalizedKey = normalizedMetadataKey(key)
                            val isEntrypoint = normalizedKey in entrypointKeys
                            val isCommand = normalizedKey == "command"
                            isEntrypoint && hasForbiddenEntrypointTarget(value) ||
                                isCommand && hasForbiddenNodeCommandText(value)
                        }
                        val nestedJavaScriptMetadata = mutableListOf<Boolean>()
                        val nestedEntrypointKeys = setOf("bin", "exports", "browser")
                        val nestedMetadataKeys = nestedEntrypointKeys + "scripts"
                        val pendingMetadataValues = ArrayDeque<Pair<Any, String?>>()
                        if (parsedJson != null) {
                            pendingMetadataValues.add(parsedJson to null)
                        }
                        while (pendingMetadataValues.isNotEmpty()) {
                            val (jsonValue, parentKey) = pendingMetadataValues.removeFirst()
                            when (jsonValue) {
                                is Map<*, *> -> jsonValue.forEach { (key, nestedValue) ->
                                    val normalizedKey = (key as? String)?.let(::normalizedMetadataKey)
                                    if (nestedValue is String &&
                                        (parentKey in nestedMetadataKeys ||
                                                normalizedKey == "command")
                                    ) {
                                        nestedJavaScriptMetadata +=
                                            parentKey in nestedEntrypointKeys &&
                                                hasForbiddenEntrypointTarget(nestedValue) ||
                                                (parentKey == "scripts" || normalizedKey == "command") &&
                                                hasForbiddenNodeCommandText(nestedValue)
                                    }
                                    if (nestedValue != null) {
                                        pendingMetadataValues.add(
                                            nestedValue to
                                                    (normalizedKey?.takeIf { it in nestedMetadataKeys } ?: parentKey)
                                        )
                                    }
                                }

                                is Iterable<*> -> jsonValue.forEach { nestedValue ->
                                    if (nestedValue != null) {
                                        pendingMetadataValues.add(nestedValue to parentKey)
                                    }
                                }
                            }
                        }
                        if (isPackagingMetadata) {
                            javascriptMetadata ||
                                nestedJavaScriptMetadata.any { it } ||
                                parsedJson != null && hasNodeOptionsMetadata(parsedJson) ||
                                parsedJson != null && hasForbiddenStructuredNodeInvocation(parsedJson)
                        } else {
                            nodeRuntime ||
                                javascriptMetadata ||
                                nestedJavaScriptMetadata.any { it } ||
                                parsedJson != null && hasForbiddenStructuredNodeInvocation(parsedJson)
                        }
                    } else {
                        false
                    }
                    if (forbiddenReferences.containsMatchIn(contents) || forbiddenNodeMetadata) {
                        violations += "forbidden production/build reference: $normalizedPath"
                    }
                }
            }
        }

        check(violations.isEmpty()) {
            "Early worktree cutover is incomplete:\n${violations.joinToString("\n")}"
        }
    }
}

tasks.named("check") {
    dependsOn(cutoverCheck)
    dependsOn(mcpSdkSnapshotCheck)
}
