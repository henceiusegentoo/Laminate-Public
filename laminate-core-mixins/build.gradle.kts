/**
 * java + Lombok + toolchain(21) + mavenCentral() are already applied via the root `subprojects`
 * block.
 *
 * Phase 4 Task 1: laminate-core-mixins replicates a handful of Paper-internal UX behaviors the old
 * Laminate fork implemented via direct source edits, as Laminate's own always-on mixin configs
 * instead - targeting vanilla Paper's unmodified classes via the exact same mixin pipeline hybrid
 * plugins already use (see test-plugin/build.gradle.kts for the proven paperweight userdev + dev
 * bundle pattern this mirrors).
 *
 * `sponge-mixin` is `compileOnly` (matching gradle/libs.versions.toml's `spongeMixin` coordinate) -
 * this module's compiled output gets woven into Paper's own classes by Sponge Mixin at runtime, but
 * never ships its own copy of the mixin library; that copy is already provided by
 * `laminate-runtime`'s `implementation` dependency and merged into the final server jar once (a
 * later task wires laminate-core-mixins' own compiled output + resources into that same merge).
 *
 * `laminate-bridge` is `implementation`, not `compileOnly`, because unlike sponge-mixin there is no
 * other copy of `LaminateBridge` merged into the final jar independently of this module - core
 * mixins genuinely need it present at runtime, not just at compile time.
 */

/**
 * Version is declared once, at the root (`apply false`) - see root build.gradle.kts's own KDoc
 * for why this must not repeat `version "2.0.0-beta.21"` here.
 */
plugins {
    id("io.papermc.paperweight.userdev")
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.fabricmc.net/")
}

dependencies {
    compileOnly(libs.sponge.mixin)
    implementation(project(":laminate-bridge"))

    /**
     * NMS + Paper API via paperweight userdev (Mojang-mapped, required for mixin development).
     * See test-plugin/build.gradle.kts's matching comment: uses gradle.properties'
     * `paperDevBundleVersion` directly (the developer-supplied full dev-bundle coordinate
     * string), not a suffix derived from `paperVersion`.
     */
    paperweight.paperDevBundle(providers.gradleProperty("paperDevBundleVersion").get())
}
