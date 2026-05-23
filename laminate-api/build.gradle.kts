plugins {
    id("java")
}

group = "eu.lschreiber.laminateapi"
version = "1.21.11-R0.1-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven("https://repo.papermc.io/repository/maven-public/")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
    // Mixin annotations needed to read @Mixin in the annotation processor
    compileOnly("net.fabricmc:sponge-mixin:0.17.0+mixin.0.8.7")

    // Bukkit API needed by LaminateEventBridge (provided at runtime by the server)
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")

    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.mockito:mockito-core:5.17.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.17.0")
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
