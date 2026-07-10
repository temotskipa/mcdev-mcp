import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.WriteProperties
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    application
    id("com.gradleup.shadow") version libs.versions.shadow.get()
}

val applicationVersion = providers.gradleProperty("version").get()

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
    implementation(libs.gson)
    implementation(libs.vineflower)
    implementation(libs.tiny.remapper)
    implementation(libs.slf4j.nop)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("dev.mcdevmcp.app.Main")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
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
    systemProperty("mcdevMcpVersion", applicationVersion)
    systemProperty("mcdevMcpJar", layout.buildDirectory.file("libs/mcdev-mcp-$applicationVersion.jar").get().asFile.absolutePath)
}

tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("mcdev-mcp")
    archiveClassifier.set("")
    archiveVersion.set(applicationVersion)
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    manifest {
        attributes[
            "Main-Class"
        ] = application.mainClass.get()
        attributes["Implementation-Version"] = applicationVersion
    }
}

tasks.named("build") {
    dependsOn(tasks.named("shadowJar"))
}
