package eu.lschreiber.laminate.runtime;

import eu.lschreiber.laminate.bridge.LaminateBridge;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.quiltmc.loader.impl.launch.common.QuiltLauncherBase;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4: proves {@link LaminateRuntimeBootstrap} Stage A now always initializes the real
 * Quilt/Mixin machinery and installs the gate transformer — even with zero hybrid plugins present,
 * since {@code laminate-core-mixins}' own always-on config is queued unconditionally now (see
 * {@code LaminateRuntimeBootstrap.CORE_MIXINS_CONFIG}'s javadoc and
 * {@code docs/plans/phase-4-core-mixins.md}'s Global Constraints "Registration" section).
 *
 * <p>Deliberately kept in its own test class/file, not folded into
 * {@link LaminateRuntimeBootstrapTest} (which already exercises real Quilt/Mixin machinery via
 * {@code perPluginFailureIsolationAndRealMixinRegistration}): {@code MixinBootstrap.init()} is a
 * genuine JVM-process-wide one-shot with no reset API (confirmed live — a second call in the same
 * JVM throws {@code MixinException: Terminating MixinTransformer instance...}), and
 * {@code build.gradle.kts}'s {@code forkEvery = 1} forks a new JVM per test <em>class</em>, not per
 * method — two real-Mixin-touching test methods in the same class would still share one JVM and
 * collide. Before phase 4, only one method anywhere in this module touched the real machinery, so
 * this was never an issue; phase 4 made Stage A do so unconditionally, so the zero-hybrid-plugin
 * scenario now needs its own forked JVM too.
 */
@DisplayName("LaminateRuntimeBootstrap Stage A with zero hybrid plugins present")
class LaminateRuntimeBootstrapEmptyPluginsTest {

    @TempDir
    Path tempDir;

    /**
     * Same static-state reset rationale as {@link LaminateRuntimeBootstrapTest}'s own
     * {@code resetStaticState()} - see that method's javadoc for the full explanation of why each
     * field is reset here.
     */
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

        Field quiltLauncherField = QuiltLauncherBase.class.getDeclaredField("launcher");
        quiltLauncherField.setAccessible(true);
        quiltLauncherField.set(null, null);
        Field quiltPropertiesField = QuiltLauncherBase.class.getDeclaredField("properties");
        quiltPropertiesField.setAccessible(true);
        quiltPropertiesField.set(null, null);
    }

    /**
     * Stage A alone does not publish to {@link LaminateBridge} - with phase 4's unconditional
     * {@code ensureMixinBootstrap()}, there is no early synchronous
     * {@code LaminateBridge.recordResult(...)} path left in {@code bootstrap()}; the bridge only
     * becomes active once Stage B is actually triggered below. The plugin directory is mutated
     * with a new broken hybrid plugin between the two {@code bootstrap()} calls specifically to
     * prove the second call does not rescan: if it did, its failed set would grow to include the
     * new plugin.
     *
     * <p>Stage B is driven directly (the same technique {@link LaminateRuntimeBootstrapTest}'s
     * {@code perPluginFailureIsolationAndRealMixinRegistration} uses) to prove the gate
     * transformer genuinely was installed and that core-mixins config registration is attempted
     * even with zero hybrid plugins present. {@code laminate-core-mixins}' own resources are
     * deliberately not on this module's test classpath (a separate module, not a test dependency
     * of laminate-runtime), so this also exercises {@code registerCoreMixinsConfig()}'s own
     * failure isolation for real, not just in theory - Stage B must still complete cleanly with an
     * active, empty-loaded/failed {@code LaminateBridge}.
     */
    @Test
    @DisplayName("bootstrap() (Stage A) is idempotent, and now always initializes the Quilt/Mixin machinery and installs the gate transformer, even without any hybrid plugin present")
    void idempotentWithoutAnyHybridPlugins() throws Exception {
        Path pluginDir = tempDir.resolve("plugins");
        Files.createDirectories(pluginDir);
        buildJar(pluginDir.resolve("ordinary.jar"), Map.of(
                "plugin.yml", """
                        name: OrdinaryPlugin
                        version: 1.0
                        main: eu.lschreiber.plugin.OrdinaryPlugin
                        """
        ));

        Set<String> firstResult = LaminateRuntimeBootstrap.bootstrap(null, pluginDir, List.of(), List.of());

        assertThat(firstResult).isEmpty();
        assertThat((Boolean) getStaticField("mixinBootstrapped")).isTrue();
        assertThat(LaminateBridge.isActive()).isFalse();

        buildJar(pluginDir.resolve("late-broken.jar"), Map.of(
                "plugin.yml", """
                        name: LateBrokenPlugin
                        version: 1.0
                        main: eu.lschreiber.plugin.LateBrokenPlugin
                        hybrid: true
                        mixins:
                          - mixins.doesnotexist.json
                        """
        ));

        Set<String> secondResult = LaminateRuntimeBootstrap.bootstrap(null, pluginDir, List.of(), List.of());

        assertThat(secondResult).isSameAs(firstResult);
        assertThat(secondResult).isEmpty();
        assertThat((Boolean) getStaticField("mixinBootstrapped")).isTrue();

        ClassLoader fakeGameLoader = new URLClassLoader(new URL[0], null);
        LaminateRuntimeBootstrap.transformerForGameLoad(fakeGameLoader);

        assertThat(LaminateBridge.isActive()).isTrue();
        assertThat(LaminateBridge.loadedHybridPluginNames()).isEmpty();
        assertThat(LaminateBridge.failedHybridPluginNames()).isEmpty();
    }

    private void putTextEntry(JarOutputStream jarOut, String name, String content) throws IOException {
        jarOut.putNextEntry(new JarEntry(name));
        jarOut.write(content.getBytes(StandardCharsets.UTF_8));
        jarOut.closeEntry();
    }

    private void buildJar(Path jarPath, Map<String, String> entries) throws IOException {
        try (OutputStream fileOut = Files.newOutputStream(jarPath);
             JarOutputStream jarOut = new JarOutputStream(fileOut)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                putTextEntry(jarOut, entry.getKey(), entry.getValue());
            }
        }
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
