package io.papermc.paper.mixin;

import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.quiltmc.loader.impl.launch.knot.MixinServiceKnot;
import org.slf4j.Logger;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

/**
 * Discovers <em>hybrid</em> plugin JARs and integrates a fully-functional
 * Quilt/Knot mixin environment into Paper's server runtime.
 *
 * <h2>What is a hybrid plugin?</h2>
 * <p>A standard Bukkit/Paper plugin whose {@code plugin.yml} contains
 * {@code hybrid: true} and a {@code mixins} list pointing at Mixin JSON
 * configuration files inside the JAR.</p>
 *
 * <h2>Architecture</h2>
 * <p>The hybrid mixin system consists of three cooperating components:</p>
 * <ul>
 *   <li>{@link PaperMixinAgent} — captures the JVM's {@link Instrumentation}
 *       instance at startup via {@code -javaagent}.</li>
 *   <li>{@link PaperQuiltLauncher} — satisfies the contract expected by
 *       {@code MixinServiceKnot}, providing class bytes and resources.</li>
 *   <li>{@link PaperMixinClassFileTransformer} — delegates to the Mixin
 *       {@link IMixinTransformer} for every class loaded by the JVM.</li>
 * </ul>
 *
 * <h2>Bootstrap sequence</h2>
 * <ol>
 *   <li>Plugin JARs are scanned via {@link PluginJarScanner}.</li>
 *   <li>Each JAR is inspected by {@link HybridDescriptorReader}.</li>
 *   <li>The Mixin subsystem is initialized ({@link MixinBootstrap#init()}).</li>
 *   <li>Mixin configs are registered; phases advance to DEFAULT.</li>
 *   <li>A {@link PaperMixinClassFileTransformer} is installed via
 *       {@link Instrumentation#addTransformer}.</li>
 * </ol>
 *
 * <h2>Thread safety</h2>
 * <p>Designed to run on the server's main thread. The volatile flags guard
 * against accidental re-initialisation but do not provide full thread safety
 * for concurrent callers.</p>
 *
 * @see PaperQuiltLauncher
 * @see PaperMixinAgent
 * @see PaperMixinClassFileTransformer
 */
public final class HybridPluginMixinBootstrap {

    private static final Logger LOGGER = LogUtils.getClassLogger();

    /**
     * Class loaders kept alive for the server's lifetime so the Mixin
     * transformer can continue resolving mixin classes from plugin JARs.
     */
    private static final List<URLClassLoader> PINNED_MIXIN_LOADERS = new ArrayList<>();

    /**
     * Names (original case, as declared in {@code plugin.yml}) of hybrid
     * plugins whose mixin configs were successfully injected.
     */
    private static final Set<String> LOADED_HYBRID_PLUGIN_NAMES = new HashSet<>();

    private static volatile boolean mixinBootstrapped;
    private static volatile boolean mixinPhasesFinished;
    private static PaperQuiltLauncher launcher;
    private static volatile Set<String> cachedFailedPlugins;

    private HybridPluginMixinBootstrap() {
    }

    /**
     * Returns an unmodifiable view of the names (original case, as declared
     * in {@code plugin.yml}) of hybrid plugins whose mixin configs were
     * successfully injected during bootstrap.
     *
     * @return unmodifiable set of successfully bootstrapped hybrid plugin names
     */
    public static Set<String> getLoadedHybridPluginNames() {
        return Collections.unmodifiableSet(LOADED_HYBRID_PLUGIN_NAMES);
    }

    /**
     * Registers a plugin-specific {@link URLClassLoader} with the launcher so
     * the Mixin transformer can resolve plugin resources at class-load time.
     *
     * @param loader the class loader to register
     */
    static void registerPluginClassLoader(final URLClassLoader loader) {
        if (launcher != null) {
            launcher.registerPluginClassLoader(loader);
        }
    }

    /**
     * Scans plugin directories for hybrid plugin JARs, initializes the Mixin
     * environment, and installs a {@link java.lang.instrument.ClassFileTransformer}
     * so all subsequently-loaded classes are transparently transformed.
     *
     * <p>This method is idempotent — subsequent calls return the cached result.</p>
     *
     * @param defaultPluginDir the primary plugins directory
     * @param addPluginFiles   additional individual plugin JAR files
     * @param addPluginDirs    additional directories whose JARs should be scanned
     * @return lower-case names of plugins whose mixin bootstrap failed
     */
    public static Set<String> injectHybridMixins(final Path defaultPluginDir,
                                                 final List<Path> addPluginFiles,
                                                 final List<Path> addPluginDirs) {
        if (cachedFailedPlugins != null) {
            return cachedFailedPlugins;
        }

        final Set<Path> pluginJars = PluginJarScanner.collectPluginJars(
                defaultPluginDir, addPluginFiles, addPluginDirs);
        final Set<String> failedPlugins = new HashSet<>();
        boolean anyInjected = false;

        for (final Path pluginJar : pluginJars) {
            final boolean injected = processPluginJar(pluginJar, failedPlugins);
            anyInjected |= injected;
        }

        if ((anyInjected || mixinBootstrapped) && !mixinPhasesFinished) {
            finishMixinPhases();
            installClassFileTransformer();
            mixinPhasesFinished = true;
        }

        if (!failedPlugins.isEmpty()) {
            LOGGER.warn("Hybrid mixin bootstrap failed for {} plugin(s). They will be disabled.",
                    failedPlugins.size());
        }
        cachedFailedPlugins = Set.copyOf(failedPlugins);
        return cachedFailedPlugins;
    }

    /**
     * Processes a single plugin JAR: reads descriptor, injects mixin configs.
     *
     * @param pluginJar     the JAR to process
     * @param failedPlugins accumulator for plugins that failed to bootstrap
     * @return {@code true} if mixin configs were successfully injected
     */
    private static boolean processPluginJar(final Path pluginJar,
                                            final Set<String> failedPlugins) {
        HybridDescriptor descriptor = null;
        try {
            descriptor = HybridDescriptorReader.read(pluginJar);
            if (descriptor == null) {
                return false;
            }
            injectDescriptor(pluginJar, descriptor);
            return true;
        } catch (final Throwable throwable) {
            final String pluginName = descriptor == null
                    ? pluginJar.getFileName().toString()
                    : descriptor.name();
            LOGGER.warn("Hybrid mixin bootstrap failed for plugin {} ({}). Plugin will be disabled.",
                    pluginName, pluginJar, throwable);
            failedPlugins.add(pluginName.toLowerCase(Locale.ENGLISH));
            return false;
        }
    }

    /**
     * Registers the mixin configurations from the given descriptor with the
     * Mixin subsystem. A dedicated {@link URLClassLoader} exposes the plugin
     * JAR's resources.
     */
    private static void injectDescriptor(final Path pluginJar,
                                         final HybridDescriptor descriptor) throws IOException {
        ensureMixinBootstrap();
        final URL pluginUrl = pluginJar.toUri().toURL();

        final URLClassLoader mixinResourceLoader = new URLClassLoader(
                new URL[]{pluginUrl},
                Thread.currentThread().getContextClassLoader());

        PINNED_MIXIN_LOADERS.add(mixinResourceLoader);
        registerPluginClassLoader(mixinResourceLoader);

        registerMixinConfigs(mixinResourceLoader, descriptor);
        LOADED_HYBRID_PLUGIN_NAMES.add(descriptor.name());
    }

    /**
     * Temporarily switches the thread-context class loader to the plugin's
     * loader and registers each mixin config with the Mixin subsystem.
     */
    private static void registerMixinConfigs(final URLClassLoader loader,
                                             final HybridDescriptor descriptor) {
        final Thread thread = Thread.currentThread();
        final ClassLoader previous = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(loader);
            for (final String config : descriptor.mixinConfigs()) {
                Mixins.addConfiguration(config);
            }
            LOGGER.info("Injected {} mixin config(s) for hybrid plugin {}",
                    descriptor.mixinConfigs().size(), descriptor.name());
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /**
     * One-time Mixin subsystem initialisation: creates the
     * {@link PaperQuiltLauncher}, sets the Mixin service, and calls
     * {@link MixinBootstrap#init()}.
     * {@link MixinExtrasBootstrap#init()}
     */
    private static synchronized void ensureMixinBootstrap() {
        if (mixinBootstrapped) {
            return;
        }
        launcher = new PaperQuiltLauncher();
        System.setProperty("mixin.service", MixinServiceKnot.class.getName());
        MixinBootstrap.init();
        MixinExtrasBootstrap.init();

        mixinBootstrapped = true;
    }

    /**
     * Advances the Mixin environment from PREINIT → INIT → DEFAULT.
     *
     * <p>{@code MixinEnvironment.gotoPhase} is package-private; reflection is
     * the only option — the same technique used by QuiltLauncherBase.</p>
     */
    private static void finishMixinPhases() {
        try {
            final Method gotoPhase = MixinEnvironment.class.getDeclaredMethod(
                    "gotoPhase", MixinEnvironment.Phase.class);
            gotoPhase.setAccessible(true);
            gotoPhase.invoke(null, MixinEnvironment.Phase.INIT);
            gotoPhase.invoke(null, MixinEnvironment.Phase.DEFAULT);
        } catch (final ReflectiveOperationException e) {
            throw new RuntimeException("Failed to advance Mixin environment phases", e);
        }
    }

    /**
     * Obtains the {@link IMixinTransformer} from {@code MixinServiceKnot} and
     * installs it as a JVM-wide {@link java.lang.instrument.ClassFileTransformer}.
     *
     * <p>After installation, already-loaded {@code net.minecraft.*} classes
     * are retransformed so mixin modifications apply retroactively.</p>
     */
    private static void installClassFileTransformer() {
        final IMixinTransformer mixinTransformer = obtainMixinTransformer();
        if (mixinTransformer == null) {
            return;
        }

        final Instrumentation instrumentation = PaperMixinAgent.getInstrumentation();
        if (instrumentation == null) {
            LOGGER.warn("No Instrumentation available — run the self-launching laminate-server.jar, "
                    + "or start the server with -javaagent:<laminate-server.jar>.");
            LOGGER.warn("Mixin transformations will NOT be applied to classes at load time.");
            return;
        }

        final PaperMixinClassFileTransformer transformer =
                new PaperMixinClassFileTransformer(mixinTransformer);
        instrumentation.addTransformer(transformer, true);
        LOGGER.info("Installed PaperMixinClassFileTransformer — hybrid mixin transformations are now active.");

        retransformAlreadyLoadedClasses(instrumentation);
    }

    /**
     * Reflectively obtains the {@link IMixinTransformer} from
     * {@code MixinServiceKnot.getTransformer()}.
     *
     * @return the transformer, or {@code null} if retrieval failed
     */
    private static IMixinTransformer obtainMixinTransformer() {
        try {
            final Method getTransformer = MixinServiceKnot.class.getDeclaredMethod("getTransformer");
            getTransformer.setAccessible(true);
            final IMixinTransformer transformer = (IMixinTransformer) getTransformer.invoke(null);
            if (transformer == null) {
                LOGGER.error("MixinServiceKnot returned a null transformer — mixin transformations will not be applied.");
            }
            return transformer;
        } catch (final ReflectiveOperationException e) {
            LOGGER.error("Failed to obtain IMixinTransformer from MixinServiceKnot", e);
            return null;
        }
    }

    /**
     * Finds all {@code net.minecraft.*} classes that are already loaded and
     * retransforms them individually via {@link Instrumentation#retransformClasses}.
     *
     * <p>This is a safety net: if the mixin bootstrap runs early enough, no
     * classes will need retransformation. Individual retransform ensures
     * method-only mixins succeed even when field-adding mixins cannot.</p>
     */
    private static void retransformAlreadyLoadedClasses(final Instrumentation instrumentation) {
        final List<Class<?>> toRetransform = findRetransformableMinecraftClasses(instrumentation);
        if (toRetransform.isEmpty()) {
            return;
        }

        LOGGER.info("Retransforming {} already-loaded Minecraft classes for mixin support...",
                toRetransform.size());

        int transformed = 0;
        for (final Class<?> clazz : toRetransform) {
            if (tryRetransform(instrumentation, clazz)) {
                transformed++;
            }
        }

        if (transformed > 0) {
            LOGGER.info("Retransformed {} classes", transformed);
        }
    }

    private static List<Class<?>> findRetransformableMinecraftClasses(
            final Instrumentation instrumentation) {
        final Class<?>[] allLoaded = instrumentation.getAllLoadedClasses();
        final List<Class<?>> result = new ArrayList<>();
        for (final Class<?> clazz : allLoaded) {
            if (clazz.getName().startsWith("net.minecraft.")
                    && instrumentation.isModifiableClass(clazz)) {
                result.add(clazz);
            }
        }
        return result;
    }

    /**
     * Attempts to retransform a single class. Returns {@code true} on success.
     */
    private static boolean tryRetransform(final Instrumentation instrumentation,
                                          final Class<?> clazz) {
        try {
            instrumentation.retransformClasses(clazz);
            return true;
        } catch (final UnsupportedOperationException e) {
            LOGGER.warn("Cannot retransform {} (schema change required — "
                    + "class was loaded before mixin bootstrap)", clazz.getName());
        } catch (final Exception e) {
            LOGGER.warn("Failed to retransform {}", clazz.getName(), e);
        }
        return false;
    }
}
