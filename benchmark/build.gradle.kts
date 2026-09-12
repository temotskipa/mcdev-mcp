plugins {
    `java-library`
    id("org.gradlex.extra-java-module-info")
}

val testJavaFeature = providers.gradleProperty("testJavaVersion").orElse("26").map { configuredVersion ->
    configuredVersion.toInt().also { feature ->
        require(feature == 26) { "Java 26 is required for testJavaVersion, got $feature" }
    }
}
val testJavaLauncher = javaToolchains.launcherFor {
    languageVersion.set(testJavaFeature.map(JavaLanguageVersion::of))
}

java {
    modularity.inferModulePath.set(true)
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(26))
    }
}

dependencies {
    implementation(project(":"))
    implementation(project(":storage-model"))
    implementation(project(":mcp-tool-api"))
    implementation("io.modelcontextprotocol.sdk:mcp-core:2.0.1")
    implementation("io.modelcontextprotocol.sdk:mcp-json-jackson3:2.0.1")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

apply(from = rootProject.file("gradle/mcp-sdk-modules.gradle"))

extraJavaModuleInfo {
    automaticModule("net.fabricmc:tiny-remapper", "net.fabricmc.tinyremapper")
}

val applicationExports = listOf(
    "dev.mcdevmcp/dev.mcdevmcp.analysis.callgraph=dev.mcdevmcp.benchmark",
    "dev.mcdevmcp/dev.mcdevmcp.analysis.index=dev.mcdevmcp.benchmark",
    "dev.mcdevmcp/dev.mcdevmcp.storage.callgraph=dev.mcdevmcp.benchmark",
    "dev.mcdevmcp/dev.mcdevmcp.storage.h2=dev.mcdevmcp.benchmark",
    "dev.mcdevmcp/dev.mcdevmcp.support=dev.mcdevmcp.benchmark",
)

tasks.withType<JavaCompile>().configureEach {
    options.release.set(26)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-preview", "-Xlint:-requires-automatic", "-Xlint:-requires-transitive-automatic", "-Werror", "--enable-preview"))
}

tasks.named<JavaCompile>("compileJava") {
    applicationExports.forEach { export ->
        options.compilerArgs.addAll(listOf("--add-exports", export))
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    javaLauncher.set(testJavaLauncher)
    jvmArgs("--enable-preview")
    filter.isFailOnNoMatchingTests = false
}
