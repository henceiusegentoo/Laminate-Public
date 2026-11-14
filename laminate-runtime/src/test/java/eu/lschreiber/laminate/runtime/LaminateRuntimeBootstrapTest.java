package eu.lschreiber.laminate.runtime;

import eu.lschreiber.laminate.bridge.LaminateBridge;
import eu.lschreiber.laminate.runtime.fixture.MixinFixtureMixin;
import eu.lschreiber.laminate.runtime.fixture.MixinFixtureTarget;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link LaminateRuntimeBootstrap}'s two-stage ("lazy gate") design.
 *
 * <p>Per the phase 3 task 4 brief (and re-confirmed by task 9's restructuring), the real Quilt/Mixin
 * machinery this class drives can only be <em>fully</em> proven live, wired into an actual server
 * boot (Task 7/9). This suite focuses on what is realistically provable standalone:
 * <ul>
 *   <li>The zero-hybrid-plugin Stage A scenario now also touches the real Quilt/Mixin machinery
 *       (phase 4 made {@code ensureMixinBootstrap()} unconditional), so it lives in its own file,
 *       {@link LaminateRuntimeBootstrapEmptyPluginsTest} — see that class's javadoc for why it can't
 *       share a JVM with this one (Sponge Mixin's {@code MixinBootstrap.init()} is a genuine
 *       process-wide one-shot, and {@code build.gradle.kts}'s {@code forkEvery = 1} only forks a new
 *       JVM per test <em>class</em>, not per method).</li>
 *   <li>{@link #perPluginFailureIsolationAndRealMixinRegistration()} — a fixture directory with one
 *       genuinely valid hybrid plugin (a real, compiled {@code @Mixin} + target class pair, per
 *       {@link MixinFixtureMixin}/{@link MixinFixtureTarget}) and one deliberately broken one
 *       (missing mixin config resource). Runs Stage A via {@code bootstrap(...)}, then drives Stage B
 *       directly by calling the package-private {@link LaminateRuntimeBootstrap#transformerForGameLoad}
 *       entry point with a freshly-constructed {@link URLClassLoader} standing in for the real
 *       {@code serverLoader} - the same call {@link LaminateMixinTransformer#transform} makes for
 *       real, from inside a live boot. A real {@code -javaagent}-supplied
 *       {@link java.lang.instrument.Instrumentation} still is not available in a plain JUnit JVM (so
 *       {@code null} is passed to {@code bootstrap(...)}, same as before task 9), but Stage B's own
 *       mechanics - real {@code MixinBootstrap.init()} → {@code Mixins.addConfiguration(...)} →
 *       {@code MixinEnvironment} phase-advance → {@code MixinServiceKnot.getTransformer()} - run for
 *       real and are not stubbed. This proves both that the valid plugin's name ends up in the loaded
 *       set and that the broken plugin's failure doesn't stop it, and that Stage B itself is a genuine
 *       one-shot: a second {@code transformerForGameLoad} call with a different candidate loader
 *       returns the exact same transformer instance without re-running registration.</li>
 * </ul>
 */
@DisplayName("LaminateRuntimeBootstrap")
class LaminateRuntimeBootstrapTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void resetStaticState() throws Exception {
        setStaticField("cachedFailedPlugins", null);
        setStaticField("mixinBootstrapped", false);
        setStaticField("launcher", null);
        setStaticField("instrumentation", null);
        setStaticField("excludedTriggerLoader", null);
        setStaticField("excludedBundlerLoader", null);
        setStaticField("stageBAttempted", false);
        setStaticField("stageBTransformer", null);
        ((Set<?>) getStaticField("LOADED_HYBRID_PLUGIN_NAMES")).clear();
        ((List<?>) getStaticField("PENDING_HYBRID_PLUGINS")).clear();
        List<?> pinnedLoaders = (List<?>) getStaticField("PINNED_MIXIN_LOADERS");
        for (Object loader : pinnedLoaders) {
            ((URLClassLoader) loader).close();
        }
        pinnedLoaders.clear();

        Field bridgeResultField = LaminateBridge.class.getDeclaredField("RESULT");
        bridgeResultField.setAccessible(true);
        ((AtomicReference<?>) bridgeResultField.get(null)).set(null);

        Field pinnedGameLoaderField = LaminateQuiltLauncher.class.getDeclaredField("pinnedGameLoader");
        pinnedGameLoaderField.setAccessible(true);
        ((AtomicReference<?>) pinnedGameLoaderField.get(null)).set(null);
    }

    @Test
    @DisplayName("a broken plugin's failure is isolated from a valid plugin's real mixin registration, deferred to Stage B and triggered exactly once")
    void perPluginFailureIsolationAndRealMixinRegistration() throws Exception {
        Path pluginDir = tempDir.resolve("plugins");
        Files.createDirectories(pluginDir);

        buildValidHybridPluginJar(pluginDir.resolve("valid.jar"));
        buildBrokenHybridPluginJar(pluginDir.resolve("broken.jar"));

        Set<String> firstResult = LaminateRuntimeBootstrap.bootstrap(null, pluginDir, List.of(), List.of());

        assertThat(firstResult).containsExactly("broken.jar");

        assertThat((Boolean) getStaticField("mixinBootstrapped")).isTrue();
        assertThat(LaminateBridge.isActive()).isFalse();

        ClassLoader fakeGameLoader = new URLClassLoader(new URL[0], null);
        IMixinTransformer transformer = LaminateRuntimeBootstrap.transformerForGameLoad(fakeGameLoader);

        assertThat(transformer).isNotNull();
        assertThat(LaminateBridge.isActive()).isTrue();
        assertThat(LaminateBridge.loadedHybridPluginNames()).containsExactly("ValidHybridPlugin");
        assertThat(LaminateBridge.failedHybridPluginNames()).containsExactly("broken.jar");
        assertThat(LaminateQuiltLauncher.pinnedGameLoader()).isSameAs(fakeGameLoader);

        ClassLoader anotherCandidateLoader = new URLClassLoader(new URL[0], null);
        IMixinTransformer secondCallResult = LaminateRuntimeBootstrap.transformerForGameLoad(anotherCandidateLoader);

        assertThat(secondCallResult).isSameAs(transformer);
        assertThat(LaminateQuiltLauncher.pinnedGameLoader()).isSameAs(fakeGameLoader);
        assertThat(LaminateBridge.loadedHybridPluginNames()).containsExactly("ValidHybridPlugin");
        assertThat(LaminateBridge.failedHybridPluginNames()).containsExactly("broken.jar");

        buildBrokenHybridPluginJar(pluginDir.resolve("broken2.jar"), "SecondBrokenPlugin");

        Set<String> secondBootstrapResult = LaminateRuntimeBootstrap.bootstrap(null, pluginDir, List.of(), List.of());

        assertThat(secondBootstrapResult).isSameAs(firstResult);
        assertThat(secondBootstrapResult).containsExactly("broken.jar");
        assertThat(LaminateBridge.loadedHybridPluginNames()).containsExactly("ValidHybridPlugin");
        assertThat(LaminateBridge.failedHybridPluginNames()).containsExactly("broken.jar");
    }

    private void buildValidHybridPluginJar(Path jarPath) throws IOException {
        String targetBinaryName = MixinFixtureTarget.class.getName();
        String mixinBinaryName = MixinFixtureMixin.class.getName();
        String mixinPackage = MixinFixtureMixin.class.getPackageName();
        String mixinSimpleName = MixinFixtureMixin.class.getSimpleName();

        byte[] targetBytes = readClassBytes(targetBinaryName);
        byte[] mixinBytes = readClassBytes(mixinBinaryName);

        String mixinConfigJson = """
                {
                  "required": true,
                  "package": "%s",
                  "compatibilityLevel": "JAVA_17",
                  "mixins": ["%s"],
                  "injectors": { "defaultRequire": 1 }
                }
                """.formatted(mixinPackage, mixinSimpleName);

        try (OutputStream fileOut = Files.newOutputStream(jarPath);
             JarOutputStream jarOut = new JarOutputStream(fileOut)) {
            putTextEntry(jarOut, "plugin.yml", """
                    name: ValidHybridPlugin
                    version: 1.0
                    main: eu.lschreiber.testfixture.ValidHybridPlugin
                    hybrid: true
                    mixins:
                      - mixins.fixture.json
                    """);
            putTextEntry(jarOut, "mixins.fixture.json", mixinConfigJson);
            putBinaryEntry(jarOut, targetBinaryName.replace('.', '/') + ".class", targetBytes);
            putBinaryEntry(jarOut, mixinBinaryName.replace('.', '/') + ".class", mixinBytes);
        }
    }

    private void buildBrokenHybridPluginJar(Path jarPath) throws IOException {
        buildBrokenHybridPluginJar(jarPath, "BrokenHybridPlugin");
    }

    private void buildBrokenHybridPluginJar(Path jarPath, String pluginName) throws IOException {
        try (OutputStream fileOut = Files.newOutputStream(jarPath);
             JarOutputStream jarOut = new JarOutputStream(fileOut)) {
            putTextEntry(jarOut, "plugin.yml", """
                    name: %s
                    version: 1.0
                    main: eu.lschreiber.testfixture.BrokenHybridPlugin
                    hybrid: true
                    mixins:
                      - mixins.doesnotexist.json
                    """.formatted(pluginName));
        }
    }

    private byte[] readClassBytes(String binaryName) throws IOException {
        String resourcePath = "/" + binaryName.replace('.', '/') + ".class";
        try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Could not locate compiled class on test classpath: " + resourcePath);
            }
            return in.readAllBytes();
        }
    }

    private void putTextEntry(JarOutputStream jarOut, String name, String content) throws IOException {
        putBinaryEntry(jarOut, name, content.getBytes(StandardCharsets.UTF_8));
    }

    private void putBinaryEntry(JarOutputStream jarOut, String name, byte[] content) throws IOException {
        jarOut.putNextEntry(new JarEntry(name));
        jarOut.write(content);
        jarOut.closeEntry();
    }

    private static Object getStaticField(String name) throws Exception {
        Field field = LaminateRuntimeBootstrap.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static void setStaticField(String name, Object value) throws Exception {
        Field field = LaminateRuntimeBootstrap.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }
}
