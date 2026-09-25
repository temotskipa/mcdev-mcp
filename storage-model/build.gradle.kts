plugins {
    `java-library`
    id("org.gradlex.extra-java-module-info")
}

apply(from = rootProject.file("gradle/java-preview.gradle.kts"))

dependencies {
    api(project(":mcp-tool-api"))
    api("com.fasterxml.jackson.core:jackson-annotations:3.0-rc5")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

apply(from = rootProject.file("gradle/mcp-sdk-modules.gradle"))

tasks.withType<Test>().configureEach {
    filter.isFailOnNoMatchingTests = false
}