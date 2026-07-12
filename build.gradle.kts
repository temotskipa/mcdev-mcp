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
                        val jsonFields = mutableListOf<Pair<String, String>>()
                        val parsedJson = runCatching { JsonSlurper().parseText(contents) }
                            .getOrElse { cause ->
                                violations += "invalid inspected JSON metadata: $normalizedPath (${cause.message})"
                                null
                            }
                        val pendingJsonValues = ArrayDeque<Any>()
                        if (parsedJson != null) {
                            pendingJsonValues.add(parsedJson)
                        }
                        while (pendingJsonValues.isNotEmpty()) {
                            when (val jsonValue = pendingJsonValues.removeFirst()) {
                                is Map<*, *> -> jsonValue.forEach { (key, nestedValue) ->
                                    if (key is String && nestedValue is String) {
                                        jsonFields += key to nestedValue
                                    }
                                    if (nestedValue != null) {
                                        pendingJsonValues.add(nestedValue)
                                    }
                                }
                                is Iterable<*> -> jsonValue.forEach { nestedValue ->
                                    if (nestedValue != null) {
                                        pendingJsonValues.add(nestedValue)
                                    }
                                }
                            }
                        }
                        val nodeRuntime = jsonFields.any { (key, value) ->
                            val normalizedKey = key.lowercase().replace("_", "").replace("-", "")
                            (normalizedKey == "runtime" || normalizedKey == "type") &&
                                value.equals("node", ignoreCase = true) ||
                                normalizedKey == "command" &&
                                (value.equals("node", ignoreCase = true) ||
                                    value.equals("node.exe", ignoreCase = true))
                        }
                        val entrypoints = jsonFields
                            .filter { (key, _) ->
                                key.lowercase().replace("_", "").replace("-", "") == "entrypoint"
                            }
                            .map { (_, value) -> value.replace('\\', '/') }
                            .filter { entrypoint ->
                                entrypoint.substringAfterLast('.').lowercase() in
                                    setOf("js", "mjs", "cjs", "ts", "tsx")
                            }
                        if (isPackagingMetadata) {
                            entrypoints.any { entrypoint ->
                                entrypoint != "bootstrap.cjs" &&
                                    entrypoint != "packaging/mcpb/bootstrap.cjs"
                            }
                        } else {
                            nodeRuntime || entrypoints.isNotEmpty()
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
