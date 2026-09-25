plugins {
    `java-library`
    id("org.gradlex.extra-java-module-info")
}

apply(from = rootProject.file("gradle/java-preview.gradle.kts"))

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

tasks.named<JavaCompile>("compileJava") {
    applicationExports.forEach { export ->
        options.compilerArgs.addAll(listOf("--add-exports", export))
    }
}

tasks.withType<Test>().configureEach {
    filter.isFailOnNoMatchingTests = false
}
