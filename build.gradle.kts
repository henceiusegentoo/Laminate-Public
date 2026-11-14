/**
 * Declares paperweight once at the root to avoid the multi-project classloader bug it can hit when
 * several subprojects apply it with their own versioned plugin blocks.
 */
plugins {
    id("io.freefair.lombok") version "9.5.0" apply false
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21" apply false
}

allprojects {
    group = "eu.lschreiber.laminate"
    version = providers.gradleProperty("laminateVersion").getOrElse("0.1.0-SNAPSHOT")
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.freefair.lombok")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    repositories {
        mavenCentral()
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }
}
