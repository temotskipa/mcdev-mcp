plugins {
    `java-library`
    id("java-preview")
    id("mcp-sdk-modules")
}

dependencies {
    api(project(":mcp-tool-api"))
    // Jackson 3 databind loads com.fasterxml.jackson.annotation.JsonSerializeAs from the 2.x
    // annotations line. 3.0-rc5 sorts above 2.21 and does not contain that class.
    api("com.fasterxml.jackson.core:jackson-annotations:2.22")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    filter.isFailOnNoMatchingTests = false
}