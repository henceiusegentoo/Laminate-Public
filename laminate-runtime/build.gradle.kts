/**
 * java + Lombok + toolchain(21) + mavenCentral() are already applied via the root `subprojects`
 * block.
 *
 * Phase 3 Task 2 (plugin descriptor scanning) only needed `org.yaml:snakeyaml` for parsing
 * `plugin.yml`. Task 3 (quilt-loader/sponge-mixin integration - `LaminateQuiltLauncher` and
 * `LaminateMixinTransformer`) adds the full Global-Constraints dependency set: these are declared
 * `implementation` (not `compileOnly`) because, unlike `laminate-core-mixins` (which compiles
 * against a dev bundle but never ships its own copy of Paper), this module's dependencies genuinely
 * need to be merged into the final jar - there is no other copy of quilt-loader/sponge-mixin/
 * mixinextras/asm anywhere at runtime.
 */
repositories {
    maven("https://maven.quiltmc.org/repository/release/")
    maven("https://repo.spongepowered.org/repository/maven-public/")
    maven("https://maven.fabricmc.net/")
}

dependencies {
    implementation(libs.snakeyaml)
    implementation(libs.quilt.loader)
    implementation(libs.sponge.mixin)
    implementation(libs.mixinextras.common)
    implementation(libs.asm.util)
    implementation(project(":laminate-bridge"))

    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
    testImplementation("org.assertj:assertj-core:3.27.7")
}
tasks.test {
    forkEvery = 1
}
