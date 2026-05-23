import io.papermc.fill.model.BuildChannel
import io.papermc.paperweight.attribute.DevBundleOutput
import io.papermc.paperweight.util.*
import java.time.Instant

plugins {
    `java-library`
    `maven-publish`
    idea
    id("io.papermc.paperweight.core")
    id("io.papermc.fill.gradle") version "1.0.10"
}

val paperMavenPublicUrl = "https://repo.papermc.io/repository/maven-public/"
val mixinExtrasVersion = "0.5.3"

repositories {
    maven("https://maven.quiltmc.org/repository/release/")
    maven("https://repo.spongepowered.org/repository/maven-public/")
    maven("https://maven.fabricmc.net/")
}

dependencies {
    mache("io.papermc:mache:1.21.11+build.1")
    paperclip("io.papermc:paperclip:3.0.3")
}

paperweight {
    minecraftVersion = providers.gradleProperty("mcVersion")
    gitFilePatches = false

    spigot {
        enabled = true
        buildDataRef = "17f77cee7117ab9d6175f088ae8962bfd04e61a9"
        packageVersion = "v1_21_R7" // also needs to be updated in MappingEnvironment
    }

    reobfPackagesToFix.addAll(
        "co.aikar.timings",
        "com.destroystokyo.paper",
        "com.mojang",
        "io.papermc.paper",
        "ca.spottedleaf",
        "net.kyori.adventure.bossbar",
        "net.minecraft",
        "org.bukkit.craftbukkit",
        "org.spigotmc",
    )

    updatingMinecraft {
        // oldPaperCommit = "c82b438b5b4ea0b230439b8e690e34708cd11ab3"
    }
}

tasks.generateDevelopmentBundle {
    libraryRepositories.addAll(
        "https://repo.maven.apache.org/maven2/",
        paperMavenPublicUrl,
        "https://maven.quiltmc.org/repository/release/",
        "https://maven.fabricmc.net/",
    )
}

abstract class Services {
    @get:Inject
    abstract val archiveOperations: ArchiveOperations
}
val services = objects.newInstance<Services>()

if (project.providers.gradleProperty("publishDevBundle").isPresent) {
    val devBundleComponent = publishing.softwareComponentFactory.adhoc("devBundle")
    components.add(devBundleComponent)

    val devBundle = configurations.consumable("devBundle") {
        attributes.attribute(DevBundleOutput.ATTRIBUTE, objects.named(DevBundleOutput.ZIP))
        outgoing.artifact(tasks.generateDevelopmentBundle.flatMap { it.devBundleFile })
    }
    devBundleComponent.addVariantsFromConfiguration(devBundle) {}

    val runtime = configurations.consumable("serverRuntimeClasspath") {
        attributes.attribute(DevBundleOutput.ATTRIBUTE, objects.named(DevBundleOutput.SERVER_DEPENDENCIES))
        attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        extendsFrom(configurations.runtimeClasspath.get())
    }
    devBundleComponent.addVariantsFromConfiguration(runtime) {
        mapToMavenScope("runtime")
    }

    val compile = configurations.consumable("serverCompileClasspath") {
        attributes.attribute(DevBundleOutput.ATTRIBUTE, objects.named(DevBundleOutput.SERVER_DEPENDENCIES))
        attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_API))
        extendsFrom(configurations.compileClasspath.get())
    }
    devBundleComponent.addVariantsFromConfiguration(compile) {
        mapToMavenScope("compile")
    }

    tasks.withType(GenerateMavenPom::class).configureEach {
        doLast {
            val text = destination.readText()
            // Remove dependencies from pom, dev bundle is designed for gradle module metadata consumers
            destination.writeText(
                text.substringBefore("<dependencies>") + text.substringAfter("</dependencies>")
            )
        }
    }

    publishing {
        publications.create<MavenPublication>("devBundle") {
            artifactId = "dev-bundle"
            from(devBundleComponent)
        }
    }
}

val log4jPlugins = sourceSets.create("log4jPlugins")
configurations.named(log4jPlugins.compileClasspathConfigurationName) {
    extendsFrom(configurations.compileClasspath.get())
}
val alsoShade: Configuration by configurations.creating

val runtimeConfiguration by configurations.consumable("runtimeConfiguration") {
    attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
    extendsFrom(configurations.getByName(sourceSets.main.get().runtimeElementsConfigurationName))
}

// Configure mockito agent that is needed in newer java versions
val mockitoAgent = configurations.register("mockitoAgent")
abstract class MockitoAgentProvider : CommandLineArgumentProvider {
    @get:CompileClasspath
    abstract val fileCollection: ConfigurableFileCollection

    override fun asArguments(): Iterable<String> {
        return listOf("-javaagent:" + fileCollection.files.single().absolutePath)
    }
}

dependencies {
    implementation(project(":paper-api"))
    implementation(project(":laminate-api"))
    implementation("ca.spottedleaf:concurrentutil:0.0.8")
    implementation("org.jline:jline-terminal-ffm:3.27.1") // use ffm on java 22+
    implementation("org.jline:jline-terminal-jni:3.27.1") // fall back to jni on java 21
    implementation("net.minecrell:terminalconsoleappender:1.3.0")
    implementation("net.kyori:adventure-text-serializer-ansi")

    /*
      Required to add the missing Log4j2Plugins.dat file from log4j-core
      which has been removed by Mojang. Without it, log4j has to classload
      all its classes to check if they are plugins.
      Scanning takes about 1-2 seconds so adding this speeds up the server start.
     */
    implementation("org.apache.logging.log4j:log4j-core:2.24.1")
    log4jPlugins.annotationProcessorConfigurationName("org.apache.logging.log4j:log4j-core:2.24.1") // Needed to generate meta for our Log4j plugins
    runtimeOnly(log4jPlugins.output)
    alsoShade(log4jPlugins.output)

    implementation("com.velocitypowered:velocity-native:3.4.0-SNAPSHOT") {
        isTransitive = false
    }
    implementation("io.netty:netty-codec-haproxy:4.2.7.Final") // Add support for proxy protocol
    implementation("org.apache.logging.log4j:log4j-iostreams:2.24.1")
    implementation("org.ow2.asm:asm-commons:9.9.1")
    implementation("org.spongepowered:configurate-yaml:4.2.0")

    // Deps that were previously in the API but have now been moved here for backwards compat, eventually to be removed
    runtimeOnly("commons-lang:commons-lang:2.6")
    runtimeOnly("org.xerial:sqlite-jdbc:3.49.1.0")
    runtimeOnly("com.mysql:mysql-connector-j:9.2.0")
    runtimeOnly("com.lmax:disruptor:3.4.4")
    implementation("com.googlecode.json-simple:json-simple:1.1.1") { // change to runtimeOnly once Timings is removed
        isTransitive = false // includes junit
    }

    testImplementation("io.github.classgraph:classgraph:4.8.179") // For mob goal test
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testImplementation("org.junit.platform:junit-platform-suite-engine:1.12.2")
    testImplementation("org.hamcrest:hamcrest:2.2")
    testImplementation("org.mockito:mockito-core:5.14.1")
    mockitoAgent("org.mockito:mockito-core:5.14.1") { isTransitive = false } // Configure mockito agent that is needed in newer java versions
    testImplementation("org.ow2.asm:asm-tree:9.9.1")
    testImplementation("org.junit-pioneer:junit-pioneer:2.2.0") // CartesianTest

    implementation("net.neoforged:srgutils:1.0.9") // Mappings handling
    implementation("net.neoforged:AutoRenamingTool:2.0.3") // Remap plugins

    // Remap reflection
    val reflectionRewriterVersion = "0.0.3"
    implementation("io.papermc:reflection-rewriter:$reflectionRewriterVersion")
    implementation("io.papermc:reflection-rewriter-runtime:$reflectionRewriterVersion")
    implementation("io.papermc:reflection-rewriter-proxy-generator:$reflectionRewriterVersion")

    // Spark
    implementation("me.lucko:spark-api:0.1-20240720.200737-2")
    implementation("me.lucko:spark-paper:1.10.152")

    // Quilt Loader
    implementation("org.quiltmc:quilt-loader:0.30.0-beta.7")
    implementation("net.fabricmc:sponge-mixin:0.17.0+mixin.0.8.7")
    implementation("io.github.llamalad7:mixinextras-common:$mixinExtrasVersion")
    implementation("org.ow2.asm:asm-util:9.9.1")
}

tasks.jar {
    manifest {
        val git = Git(rootProject.layout.projectDirectory.path)
        val mcVersion = rootProject.providers.gradleProperty("mcVersion").get()
        val build = System.getenv("BUILD_NUMBER") ?: null
        val buildTime = providers.environmentVariable("BUILD_STARTED_AT").map(Instant::parse).orElse(Instant.EPOCH).get()
        val gitHash = git.exec(providers, "rev-parse", "--short=7", "HEAD").get().trim()
        val implementationVersion = "$mcVersion-${build ?: "DEV"}-$gitHash"
        val date = git.exec(providers, "show", "-s", "--format=%ci", gitHash).get().trim()
        val gitBranch = git.exec(providers, "rev-parse", "--abbrev-ref", "HEAD").get().trim()
        attributes(
            "Main-Class" to "org.bukkit.craftbukkit.Main",
            "Implementation-Title" to "Laminate",
            "Implementation-Version" to implementationVersion,
            "Implementation-Vendor" to date,
            "Specification-Title" to "Laminate",
            "Specification-Version" to project.version,
            "Specification-Vendor" to "Laminate Team",
            "Brand-Id" to "lschreiber:laminate",
            "Brand-Name" to "Laminate",
            "Build-Number" to (build ?: ""),
            "Build-Time" to buildTime.toString(),
            "Git-Branch" to gitBranch,
            "Git-Commit" to gitHash,
        )
        for (tld in setOf("net", "com", "org")) {
            attributes("$tld/bukkit", "Sealed" to true)
        }
    }
}

// Compile tests with -parameters for better junit parameterized test names
tasks.compileTestJava {
    options.compilerArgs.add("-parameters")
}

// Bump compile tasks to 1GB memory to avoid OOMs
tasks.withType<JavaCompile>().configureEach {
    options.forkOptions.memoryMaximumSize = "1G"
}

val scanJarForBadCalls by tasks.registering(io.papermc.paperweight.tasks.ScanJarForBadCalls::class) {
    badAnnotations.add("Lio/papermc/paper/annotation/DoNotUse;")
    jarToScan.set(tasks.jar.flatMap { it.archiveFile })
    classpath.from(configurations.compileClasspath)
}
tasks.check {
    dependsOn(scanJarForBadCalls)
}

// Use TCA for console improvements
tasks.jar {
    val archiveOperations = services.archiveOperations
    from(alsoShade.elements.map {
        it.map { f ->
            if (f.asFile.isFile) {
                archiveOperations.zipTree(f.asFile)
            } else {
                f.asFile
            }
        }
    })
}

tasks.test {
    include("**/**TestSuite.class")
    workingDir = temporaryDir
    useJUnitPlatform {
        forkEvery = 1
        excludeTags("Slow")
    }

    // Configure mockito agent that is needed in newer java versions
    val provider = objects.newInstance<MockitoAgentProvider>()
    provider.fileCollection.from(mockitoAgent)
    jvmArgumentProviders.add(provider)
}

val generatedDir: java.nio.file.Path = layout.projectDirectory.dir("src/generated/java").asFile.toPath()
idea {
    module {
        generatedSourceDirs.add(generatedDir.toFile())
    }
}
sourceSets {
    main {
        java {
            srcDir(generatedDir)
        }
    }
}

fun TaskContainer.registerRunTask(
    name: String,
    block: JavaExec.() -> Unit
): TaskProvider<JavaExec> = register<JavaExec>(name) {
    group = "runs"
    mainClass.set("org.bukkit.craftbukkit.Main")
    standardInput = System.`in`
    workingDir = rootProject.layout.projectDirectory
        .dir(providers.gradleProperty("paper.runWorkDir").getOrElse("run"))
        .asFile
    javaLauncher.set(project.javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
        vendor.set(JvmVendorSpec.JETBRAINS)
    })
    jvmArgs("-XX:+AllowEnhancedClassRedefinition")

    if (rootProject.childProjects["test-plugin"] != null) {
        val testPluginJar = rootProject.project(":test-plugin").tasks.jar.flatMap { it.archiveFile }
        inputs.file(testPluginJar)
        args("-add-plugin=${testPluginJar.get().asFile.absolutePath}")
    }

    args("--nogui")
    systemProperty("net.kyori.adventure.text.warnWhenLegacyFormattingDetected", true)
    if (providers.gradleProperty("paper.runDisableWatchdog").getOrElse("false") == "true") {
        systemProperty("disable.watchdog", true)
    }
    systemProperty("io.papermc.paper.suppress.sout.nags", true)

    val memoryGb = providers.gradleProperty("paper.runMemoryGb").getOrElse("2")
    minHeapSize = "${memoryGb}G"
    maxHeapSize = "${memoryGb}G"

    doFirst {
        workingDir.mkdirs()
    }

    block(this)
}

tasks.registerRunTask("runServer") {
    description = "Spin up a test server from the Mojang mapped server jar"
    classpath(tasks.includeMappings.flatMap { it.outputJar })
    classpath(configurations.runtimeClasspath)
}

tasks.registerRunTask("runReobfServer") {
    description = "Spin up a test server from the reobfJar output jar"
    classpath(tasks.reobfJar.flatMap { it.outputJar })
    classpath(configurations.runtimeClasspath)
}

tasks.registerRunTask("runDevServer") {
    description = "Spin up a test server without assembling a jar"
    classpath(sourceSets.main.map { it.runtimeClasspath })
}

tasks.registerRunTask("runBundler") {
    description = "Spin up a test server from the Mojang mapped bundler jar"
    classpath(tasks.createMojmapBundlerJar.flatMap { it.outputZip })
    mainClass.set(null as String?)
}
tasks.registerRunTask("runReobfBundler") {
    description = "Spin up a test server from the reobf bundler jar"
    classpath(tasks.createReobfBundlerJar.flatMap { it.outputZip })
    mainClass.set(null as String?)
}
tasks.registerRunTask("runPaperclip") {
    description = "Spin up a test server from the Mojang mapped Paperclip jar"
    classpath(tasks.createMojmapPaperclipJar.flatMap { it.outputZip })
    mainClass.set(null as String?)
}
tasks.registerRunTask("runReobfPaperclip") {
    description = "Spin up a test server from the reobf Paperclip jar"
    classpath(tasks.createReobfPaperclipJar.flatMap { it.outputZip })
    mainClass.set(null as String?)
}

fill {
    project("laminate")
    versionFamily(paperweight.minecraftVersion.map { it.split(".", "-").takeWhile { part -> part.toIntOrNull() != null }.take(2).joinToString(".") })
    version(paperweight.minecraftVersion)

    build {
        channel = BuildChannel.STABLE

        downloads {
            register("server:default") {
                file = tasks.createMojmapPaperclipJar.flatMap { it.outputZip }
                nameResolver.set { project, _, version, build -> "$project-$version-$build.jar" }
            }
        }
    }
}

// ============================================================================
// createLaminateServerJar — single distributable server JAR
// ============================================================================
// Merges the mojmap bundler JAR content with the self-launching
// LaminateLauncher class and the PaperMixinAgent class. The result is a
// single file that operators run with just:
//
//   java -jar laminate-server-<version>.jar [server args]
//
// On first launch LaminateLauncher detects that the agent is not loaded and
// relaunches the JVM with the required --add-opens flags and -javaagent:self
// automatically. No extra files to distribute, no flags to remember.
val createLaminateServerJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Assembles laminate-server-{version}.jar: a self-launching single-file distribution with bundled mixin agent."

    archiveBaseName.set("laminate-server")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("")

    // First entry wins when duplicate paths are encountered (our updated
    // classes take precedence over whatever is already in the bundler JAR).
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    dependsOn(tasks.compileJava, tasks.createMojmapBundlerJar)

    val archiveOps = services.archiveOperations

    // 1. Our new / updated classes come first so they shadow any copies
    //    already present inside the bundler JAR.
    from(sourceSets.main.get().output) {
        include(
            "io/papermc/paper/LaminateLauncher.class",
            "io/papermc/paper/mixin/PaperMixinAgent.class",
        )
    }

    // 2. Merge the entire mojmap bundler JAR (Mojang-mapped names).
    //    Note: paperweight has deprecated reobf bundler JARs — the mojmap
    //    variant is the supported production format in paperweight 2.x.
    from(tasks.createMojmapBundlerJar.flatMap { it.outputZip }.map { archiveOps.zipTree(it.asFile) }) {
        exclude("META-INF/MANIFEST.MF")
        // Strip JAR signing metadata — signatures become invalid after
        // repackaging and would cause SecurityException at runtime.
        exclude("META-INF/*.SF")
        exclude("META-INF/*.RSA")
        exclude("META-INF/*.DSA")
    }

    manifest {
        attributes(
            // Self-launching wrapper replaces the bundler's Main-Class.
            "Main-Class"              to "io.papermc.paper.LaminateLauncher",
            // Preserved original entry point; LaminateLauncher delegates here
            // after the agent is loaded so Paperclip can set up the classpath.
            "Bundler-Main-Class"      to "io.papermc.paperclip.Main",
            // Same JAR is also used as the Java agent (-javaagent:self).
            "Premain-Class"           to "io.papermc.paper.mixin.PaperMixinAgent",
            "Agent-Class"             to "io.papermc.paper.mixin.PaperMixinAgent",
            "Can-Retransform-Classes" to "true",
            "Can-Redefine-Classes"    to "true",
        )
    }
}

// Agent JAR for hybrid-plugin mixin support.
// Contains only PaperMixinAgent with the Premain-Class manifest attribute
// so that the JVM captures an Instrumentation handle at startup.
val agentJar by tasks.registering(Jar::class) {
    archiveBaseName.set("laminate-mixin-agent")
    archiveVersion.set("")
    archiveClassifier.set("")
    dependsOn(tasks.compileJava)
    from(sourceSets.main.get().output) {
        include("io/papermc/paper/mixin/PaperMixinAgent.class")
    }
    manifest {
        attributes(
            "Premain-Class" to "io.papermc.paper.mixin.PaperMixinAgent",
            "Agent-Class" to "io.papermc.paper.mixin.PaperMixinAgent",
            "Can-Retransform-Classes" to "true",
            "Can-Redefine-Classes" to "true",
        )
    }
}
