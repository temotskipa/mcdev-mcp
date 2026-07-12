import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
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
val java25Launcher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(25))
}

val generateTestVersionProperties = tasks.register<WriteProperties>("generateTestVersionProperties") {
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
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("dev.mcdevmcp.test.versionFallback", "true")
    systemProperty("mcdevMcpVersion", applicationVersion)
    systemProperty("mcdevMcpJar", layout.buildDirectory.file("libs/mcdev-mcp-$applicationVersion.jar").get().asFile.absolutePath)
    systemProperty("mcdevMcpJava", java25Launcher.get().executablePath.asFile.absolutePath)
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

tasks.register("cutoverCheck") {
    group = "verification"
    description = "Rejects tracked retired implementation surface."
    val repositoryRoot = project.layout.projectDirectory.asFile
    val forbiddenRootFiles = setOf(
        "package.json",
        "package-lock.json",
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
            "\\bMCDEV_AST_" + "PARSER\\b",
            "\\bMCDEV_" + "INDEXER\\b"
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
            if (
                normalizedPath.endsWith(".ts") ||
                normalizedPath.endsWith(".tsx") ||
                ((normalizedPath.endsWith(".mjs") || normalizedPath.endsWith(".cjs")) &&
                    normalizedPath != "packaging/mcpb/bootstrap.cjs") ||
                normalizedPath in forbiddenRootFiles ||
                normalizedPath.startsWith("java-worker/")
            ) {
                violations += "forbidden tracked file: $normalizedPath"
            }

            val mustInspectContents =
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
                    if (forbiddenReferences.containsMatchIn(contents)) {
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
