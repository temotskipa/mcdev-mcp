plugins {
    `java-library`
}

val testJavaFeature = providers.gradleProperty("testJavaVersion").orElse("25").map { configuredVersion ->
    configuredVersion.toInt().also { feature ->
        require(feature == 25 || feature == 26) { "testJavaVersion must be 25 or 26, got $feature" }
    }
}
val testJavaLauncher = javaToolchains.launcherFor {
    languageVersion.set(testJavaFeature.map(JavaLanguageVersion::of))
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    api("io.modelcontextprotocol.sdk:mcp-core:${libs.versions.mcp.get()}")

    testImplementation("io.modelcontextprotocol.sdk:mcp-json-jackson3:${libs.versions.mcp.get()}")
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    javaLauncher.set(testJavaLauncher)
}

tasks.jar {
    manifest {
        attributes["Automatic-Module-Name"] = "dev.mcdevmcp.mcp.binding"
    }
}
