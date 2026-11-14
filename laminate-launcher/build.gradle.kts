import eu.lschreiber.laminate.buildsrc.bundling.LaminateServerJarTask
import eu.lschreiber.laminate.buildsrc.paperclip.DownloadPaperclipTask
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.language.jvm.tasks.ProcessResources

/**
 * java + Lombok + toolchain(21) + mavenCentral() are already applied via the root `subprojects`
 * block. This module deliberately does NOT apply the `application` plugin (see task-3-brief.md):
 * it doesn't run as a conventional application, it produces a bundled server jar via a custom
 * `laminateServerJar` task (Task 4), on top of the paperclip jar `downloadPaperclip` fetches here.
 */

/**
 * Same extra repositories as laminate-runtime/build.gradle.kts (quilt-loader/sponge-mixin/
 * mixinextras coordinates) - each module resolves dependencies only against its own declared
 * repositories in Gradle, so this module needs them too now that it depends on laminate-runtime
 * and therefore has to resolve its own `runtimeClasspath` (used below to merge laminate-runtime's
 * full runtime dependency set into the bundled server jar) against the same coordinates.
 */
repositories {
    maven("https://maven.quiltmc.org/repository/release/")
    maven("https://repo.spongepowered.org/repository/maven-public/")
    maven("https://maven.fabricmc.net/")
}

/**
 * LaminateMain calls LaminateRuntimeBootstrap.bootstrap(...) directly (Phase 3 Task 5), and logs
 * its result via LaminateBridge - both need to be on this module's own compile classpath, not just
 * pulled in transitively for bundling purposes.
 */
dependencies {
    implementation(project(":laminate-runtime"))
    implementation(project(":laminate-bridge"))

    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
    testImplementation("org.assertj:assertj-core:3.27.7")
}

/**
 * Referenced directly below (laminate-runtime's/laminate-api's/laminate-core-mixins' own
 * `compileJava` output directory and laminate-runtime's own `runtimeClasspath` configuration) at
 * configuration time - make that safe/deterministic rather than relying on Gradle's default
 * (declaration-order) project evaluation sequence.
 */
evaluationDependsOn(":laminate-runtime")
evaluationDependsOn(":laminate-api")
evaluationDependsOn(":laminate-core-mixins")

/**
 * sponge-mixin transitively pulls in ancient `com.google.code.gson:gson:2.2.4` (2013) and
 * `com.google.guava:guava:21.0` (2017), and nothing else in this module's dependency graph pulls in
 * newer ones to win either conflict. That was invisible before Phase 3 Task 8's
 * classloader-interposition fix, because `bundlerLoader` was never actually an ancestor of
 * paperclip's server classloader, so these old classes - merged unshaded into the bundled jar via
 * `libraryJars` below - were never reached. Now that `bundlerLoader` genuinely is an ancestor,
 * ordinary parent-first delegation lets it resolve `com.google.gson.*`/`com.google.common.*` before
 * Paper's own bundled (much newer) copies get a chance, and Paper's own code needs APIs only added
 * in newer releases (`JsonReader.setStrictness` - gson 2.10.0; `ImmutableMap.Builder.buildOrThrow`
 * - guava 31.1) - crashing with `NoSuchMethodError` during boot. Forcing modern versions here (their
 * public APIs, as used by sponge-mixin's own config parsing, are unaffected across this range)
 * resolves the conflict for everything merged into the bundled jar. This is the full list of stale
 * transitive versions in this module's dependency graph - confirmed via `dependencies
 * --configuration runtimeClasspath`, not guessed.
 */
configurations.runtimeClasspath {
    resolutionStrategy {
        force("com.google.code.gson:gson:2.11.0")
        force("com.google.guava:guava:33.4.0-jre")
    }
}

val downloadPaperclip = tasks.register<DownloadPaperclipTask>("downloadPaperclip") {
    group = "laminate"
    description = "Downloads and sha256-verifies the Paper paperclip jar for `paperVersion` " +
        "via the Fill v3 API (legacy v2 fallback); see PaperclipResolver in buildSrc."

    paperVersion.set(providers.gradleProperty("paperVersion"))
    paperBuild.set(providers.gradleProperty("paperBuild"))
    outputDirectory.set(layout.buildDirectory.dir("paperclip"))
}

val laminateRuntimeCompileJava = project(":laminate-runtime").tasks.named<JavaCompile>("compileJava")
val laminateApiCompileJava = project(":laminate-api").tasks.named<JavaCompile>("compileJava")

/**
 * laminate-core-mixins (phase 4) - unlike laminate-runtime/laminate-api, it ships a resource that
 * genuinely needs to be in the final bundle at runtime (laminate-core.mixins.json, read by Sponge
 * Mixin via Mixins.addConfiguration at boot), not just compiled classes - so both its `compileJava`
 * output *and* its `processResources` output are merged below, not `compileJava` alone.
 */
val laminateCoreMixinsCompileJava = project(":laminate-core-mixins").tasks.named<JavaCompile>("compileJava")
val laminateCoreMixinsProcessResources = project(":laminate-core-mixins").tasks.named<ProcessResources>("processResources")

tasks.register<LaminateServerJarTask>("laminateServerJar") {
    group = "laminate"
    description = "Merges the downloaded paperclip jar with LaminateMain, laminate-runtime, " +
        "laminate-api, laminate-core-mixins, and their full runtime dependency set into a single " +
        "bootable laminate-server-<paperVersion>.jar."

    /**
     * Depend on downloadPaperclip explicitly for correct task ordering, and read its jar location
     * through its declared `@OutputFile` (getOutputJar()) rather than hardcoding the path - that
     * output filename embeds the resolved build number, which isn't known until that task runs.
     */
    dependsOn(
        downloadPaperclip,
        tasks.named("compileJava"),
        tasks.named("jar"),
        laminateRuntimeCompileJava,
        laminateApiCompileJava,
        laminateCoreMixinsCompileJava,
        laminateCoreMixinsProcessResources
    )

    paperclipJar.fileProvider(downloadPaperclip.map { it.outputJar })

    /**
     * First-party compiled classes (+ laminate-core-mixins' processed resources), highest merge
     * priority: this module's own LaminateMain/LaminateAgent/RelaunchHandler, laminate-runtime's
     * hybrid-plugin/mixin bootstrap classes, laminate-api's plugin-facing classes (referenced by
     * hybrid plugins and injected mixin code at runtime), and laminate-core-mixins' always-on
     * PluginInitializerManagerMixin/ReloadCommandMixin/PaperPluginsCommandMixin classes plus their
     * laminate-core.mixins.json config.
     */
    classDirectories.from(
        tasks.named<JavaCompile>("compileJava").flatMap { it.destinationDirectory },
        laminateRuntimeCompileJava.flatMap { it.destinationDirectory },
        laminateApiCompileJava.flatMap { it.destinationDirectory },
        laminateCoreMixinsCompileJava.flatMap { it.destinationDirectory },
        /**
         * ProcessResources.getDestinationDir() is a plain File property (AbstractCopyTask), not a
         * DirectoryProperty like JavaCompile's destinationDirectory above - map(), not flatMap().
         */
        laminateCoreMixinsProcessResources.map { it.destinationDir }
    )

    /**
     * laminate-runtime's own fully resolved runtime classpath: quilt-loader, sponge-mixin,
     * mixinextras-common, asm-util + its transitive asm artifacts, snakeyaml, and
     * laminate-bridge's jar - everything laminate-runtime needs at runtime beyond its own compiled
     * output (already covered above via laminateRuntimeCompileJava), reaching this module's own
     * `runtimeClasspath` transitively via the `implementation(project(":laminate-runtime"))`
     * dependency declared above.
     *
     * Deliberately resolved from *this* module's own `runtimeClasspath` configuration, filtered to
     * exclude the laminate-runtime project artifact itself, rather than reaching directly into
     * `project(":laminate-runtime").configurations.getByName("runtimeClasspath")` from here: Gradle
     * 9 rejects resolving another project's configuration from outside that project's own execution
     * context ("Resolution of the configuration ... was attempted without an exclusive lock") -
     * confirmed by hitting that exact failure with the direct-reference approach before switching to
     * this one. Filtering out the laminate-runtime project artifact avoids double-merging its
     * classes (once via the classDirectories entry above, once via its packaged jar here) - a
     * project's own runtimeClasspath does not normally include its own output, but this module's
     * *own* runtimeClasspath does include laminate-runtime's jar, since laminate-runtime is one of
     * this module's own `implementation` dependencies.
     */
    libraryJars.from(configurations.runtimeClasspath.get().incoming.artifactView {
        componentFilter { id -> !(id is ProjectComponentIdentifier && id.projectPath == ":laminate-runtime") }
    }.files)

    outputJar.set(layout.buildDirectory.file(
        providers.gradleProperty("paperVersion").map { "libs/laminate-server-$it.jar" }
    ))
}
