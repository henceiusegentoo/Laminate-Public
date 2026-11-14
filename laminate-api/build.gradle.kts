/**
 * java + Lombok + toolchain + mavenCentral() are already applied via the root `subprojects`
 * block; this file only adds what's specific to laminate-api.
 */
plugins {
    `maven-publish`
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.fabricmc.net/")
}

dependencies {
    compileOnly("net.fabricmc:sponge-mixin:0.17.3+mixin.0.8.7")

    /**
     * Bukkit API needed by LaminateEventBridge (provided at runtime by the server). Uses
     * gradle.properties' `paperDevBundleVersion` directly, as the developer-supplied full Maven
     * artifact version string, rather than deriving it from `paperVersion` - Paper's own
     * dev-bundle/paper-api coordinate scheme has changed shape before (see that property's own
     * comment in gradle.properties) and a derivation the build owns is exactly what breaks each
     * time it does.
     */
    compileOnly("io.papermc.paper:paper-api:${providers.gradleProperty("paperDevBundleVersion").get()}")

    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.mockito:mockito-core:5.17.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.17.0")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
