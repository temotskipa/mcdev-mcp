plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation("org.gradlex.extra-java-module-info:org.gradlex.extra-java-module-info.gradle.plugin:1.14.2")
}
