pluginManagement {
    repositories {
        maven("https://maven.kikugie.dev/releases")
        maven("https://maven.kikugie.dev/snapshots")
        maven("https://maven.fabricmc.net/")
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.10-beta.2"
}

stonecutter {
    create(rootProject) {
        versions("1.19.4", "1.20.1", "1.21.1", "1.21.10", "1.21.11", "26.3")
        vcsVersion = "1.21.11"
    }
}

rootProject.name = "liquidphase"
