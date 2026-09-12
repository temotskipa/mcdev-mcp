@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("org.gradlex.extra-java-module-info") version "1.14.2"
        id("com.gradleup.shadow") version "9.6.1"
    }
}

plugins {
    id("io.github.ben-manes.versions.settings") version "0.61.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/")
        maven("https://central.sonatype.com/repository/maven-snapshots/") {
            mavenContent {
                snapshotsOnly()
            }
            content {
                includeGroup("io.modelcontextprotocol.sdk")
            }
        }
    }
}

rootProject.name = "mcdev-mcp"

include("mcp-tool-api")
include("benchmark")
include("conformance")
