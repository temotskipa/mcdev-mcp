import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import groovy.json.JsonSlurper
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.WriteProperties
import org.gradle.api.tasks.compile.JavaCompile
import java.nio.charset.StandardCharsets
import java.nio.file.Files

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
    implementation(libs.mcp)
    implementation(libs.picocli)
    implementation(libs.sqlite)
    implementation(libs.vineflower)
    implementation(libs.tiny.remapper)
    implementation(libs.slf4j.nop)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("dev.mcdevmcp.app.Main")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
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
    jvmArgs("--enable-native-access=ALL-UNNAMED")
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
        // Required for Xerial SQLite JNI/FFM under JDK 24+ restricted native access.
        attributes["Enable-Native-Access"] = "ALL-UNNAMED"
    }
}

tasks.named("build") {
    dependsOn(tasks.named("shadowJar"))
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
            "\\bjava-callgraph" + "2\\b",
            "\\bjavacg(?:-static)?(?:\\.jar)?\\b",
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
                                                    (normalizedKey in environmentKeys && nestedValue is Iterable<*>)
                                            )
                                        }
                                    }
                                    is Iterable<*> -> value.filterNotNull().forEach { nestedValue ->
                                        pendingEnvironmentValues.add(
                                            nestedValue to (assignmentList && nestedValue is Iterable<*>)
                                        )
                                        if (assignmentList && nestedValue is String) {
                                            val assignment = nestedValue.indexOf('=')
                                            if (assignment > 0 &&
                                                normalizedMetadataKey(
                                                    nestedValue.substring(0, assignment).trim()
                                                ) == "nodeoptions"
                                            ) {
                                                return true
                                            }
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

                        fun runsNodeJavaScript(value: String): Boolean {
                            val nodeCommand = Regex("""(?i)\bnode(?:\.exe)?\b""").find(value)
                                ?: return false
                            if (javascriptEntrypoint.containsMatchIn(value)) {
                                return false
                            }
                            val arguments = value.substring(nodeCommand.range.last + 1).trim()
                            return arguments.isNotEmpty() &&
                                arguments !in setOf("-v", "--version", "-h", "--help")
                        }

                        fun stringArguments(value: Any?): List<String> = when (value) {
                            is String -> listOf(value)
                            is Map<*, *> -> value.values.flatMap(::stringArguments)
                            is Iterable<*> -> value.flatMap(::stringArguments)
                            else -> emptyList()
                        }

                        fun isNodeCommand(value: String) =
                            value.trim().replace('\\', '/').substringAfterLast('/')
                                .let { it.equals("node", ignoreCase = true) || it.equals("node.exe", ignoreCase = true) }

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
                                isCommand &&
                                (hasForbiddenJavaScriptEntrypoint(value) || runsNodeJavaScript(value))
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
                                                (hasForbiddenJavaScriptEntrypoint(nestedValue) ||
                                                    runsNodeJavaScript(nestedValue))
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
}
