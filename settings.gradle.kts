pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "laminate-standalone"

include("laminate-api")
include("laminate-bridge")
include("laminate-core-mixins")
include("laminate-runtime")
include("laminate-launcher")
include("test-plugin")
