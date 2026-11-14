/**
 * java + Lombok + toolchain(21) + mavenCentral() are already applied via the root `subprojects`
 * block (Lombok is unused by test-plugin's source but applied uniformly to all subprojects).
 *
 * This is the reference hybrid plugin: a small Bukkit plugin with `hybrid: true` in plugin.yml
 * that demonstrates the whole pipeline (a @Mixin targeting Paper internals, @GenerateAccessor,
 * and @InjectEvent). It is not wired into anything yet - a later task boots the server with this
 * plugin present and proves the mixin fires for real. This module only needs to compile and
 * package correctly.
 */

/**
 * Version is declared once, at the root (apply false) - see root build.gradle.kts's comment:
 * applying paperweight-userdev with its own `version` independently in two or more subprojects
 * trips a known Gradle/paperweight-userdev classloader bug once laminate-core-mixins (Phase 4
 * Task 1) started applying it too.
 */
plugins {
    id("com.gradleup.shadow") version "9.6.0"
    id("io.papermc.paperweight.userdev")
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.fabricmc.net/")
}

dependencies {
    /**
     * Mixin annotations + MixinExtras - Laminate provides both at runtime (merged into
     * laminate-server.jar from laminate-runtime's own dependency set); never shade these into the
     * plugin jar. Coordinates match laminate-runtime's (gradle/libs.versions.toml: spongeMixin,
     * mixinextrasCommon) so this module compiles against exactly what's bundled at runtime.
     */
    compileOnly("net.fabricmc:sponge-mixin:0.17.3+mixin.0.8.7")
    compileOnly("io.github.llamalad7:mixinextras-common:0.5.4")
    annotationProcessor("io.github.llamalad7:mixinextras-common:0.5.4")

    /**
     * laminate-api: @InjectEvent / @GenerateAccessor annotations and their annotation processors
     * (generates TickListener's ServerLevelTickStartEvent bridge and MinecraftServerAccessor).
     * Also provided by Laminate at runtime; never shade this either.
     */
    compileOnly(project(":laminate-api"))
    annotationProcessor(project(":laminate-api"))

    /**
     * NMS + Paper API via paperweight userdev (Mojang-mapped, required for mixin development).
     * Uses gradle.properties' `paperDevBundleVersion` directly, as the developer-supplied full
     * Maven artifact version string for the dev bundle, rather than deriving it from
     * `paperVersion` by appending a hardcoded suffix - Paper's dev-bundle coordinate scheme has
     * changed shape before (classic "<mcVersion>-R0.1-SNAPSHOT" vs. the newer
     * "<mcVersion>.build.<n>-<channel>" scheme introduced alongside Minecraft's 26.x versioning)
     * and a derivation the build owns is exactly what breaks each time it does. See
     * `paperDevBundleVersion`'s own comment in gradle.properties.
     */
    paperweight.paperDevBundle(providers.gradleProperty("paperDevBundleVersion").get())
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.processResources {
    val props = mapOf("version" to version)
    filesMatching("plugin.yml") {
        expand(props)
    }
}
