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
    api(project(":mcp-tool-api"))
    api("com.fasterxml.jackson.core:jackson-annotations:2.21")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

apply(from = rootProject.file("gradle/mcp-sdk-modules.gradle"))

tasks.withType<JavaCompile>().configureEach {
    options.release.set(26)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-preview", "-Werror", "--enable-preview"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    javaLauncher.set(testJavaLauncher)
    jvmArgs("--enable-preview")
    filter.isFailOnNoMatchingTests = false
}
