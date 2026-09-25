plugins {
    `java-library`
    id("org.gradlex.extra-java-module-info")
}

apply(from = rootProject.file("gradle/java-preview.gradle.kts"))

dependencies {
    api("io.modelcontextprotocol.sdk:mcp-core:2.0.1")
    compileOnly("jakarta.servlet:jakarta.servlet-api:6.1.0")

    testImplementation("io.modelcontextprotocol.sdk:mcp-json-jackson3:2.0.1")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

apply(from = rootProject.file("gradle/mcp-sdk-modules.gradle"))

val jpmsSmoke = sourceSets.create("jpmsSmoke")

dependencies {
    add(jpmsSmoke.implementationConfigurationName, files(tasks.named("jar")))
    add(jpmsSmoke.compileOnlyConfigurationName, "jakarta.servlet:jakarta.servlet-api:6.1.0")
    add(
        jpmsSmoke.implementationConfigurationName,
        "io.modelcontextprotocol.sdk:mcp-json-jackson3:2.0.1"
    )
}

tasks.withType<Test>().configureEach {
    filter.isFailOnNoMatchingTests = false
}

val jpmsSmokeTest = tasks.register<JavaExec>("jpmsSmokeTest") {
    group = "verification"
    description = "Compiles and runs the typed tool API and MCP SDK providers as named JPMS modules."
    dependsOn(tasks.named(jpmsSmoke.classesTaskName))
    classpath = jpmsSmoke.runtimeClasspath
    mainModule.set("dev.mcdevmcp.mcp.tool.api.smoke")
    mainClass.set("dev.mcdevmcp.mcp.tool.api.smoke.JpmsSmokeMain")
}

tasks.named("check") {
    dependsOn(jpmsSmokeTest)
}
