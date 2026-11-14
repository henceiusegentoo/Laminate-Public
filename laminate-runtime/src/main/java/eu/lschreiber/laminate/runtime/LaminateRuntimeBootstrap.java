package eu.lschreiber.laminate.runtime;

import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import eu.lschreiber.laminate.bridge.LaminateBridge;
import eu.lschreiber.laminate.bridge.LaminateLog;
import eu.lschreiber.laminate.bridge.LaminateLog.Level;
import eu.lschreiber.laminate.bridge.LaminateLogMessages;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.quiltmc.loader.impl.launch.knot.MixinServiceKnot;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

/** Coordinates hybrid-plugin discovery and the two-stage Mixin bootstrap. */
public final class LaminateRuntimeBootstrap {

    private static final LaminateLog LOGGER = LaminateLog.getLogger("laminate.runtime.LaminateRuntimeBootstrap");

    private static final String CORE_MIXINS_CONFIG = "laminate-core.mixins.json";

    private static final List<URLClassLoader> PINNED_MIXIN_LOADERS = new ArrayList<>();

    private static final List<PendingHybridPlugin> PENDING_HYBRID_PLUGINS = new ArrayList<>();

    private static final Set<String> LOADED_HYBRID_PLUGIN_NAMES = new HashSet<>();

    private static volatile boolean mixinBootstrapped;
    private static LaminateQuiltLauncher launcher;
    private static volatile Set<String> cachedFailedPlugins;

    private static volatile Instrumentation instrumentation;

    private static volatile ClassLoader excludedTriggerLoader;

    private static volatile ClassLoader excludedBundlerLoader;

    private static volatile boolean stageBAttempted;

    private static volatile IMixinTransformer stageBTransformer;

    private LaminateRuntimeBootstrap() {
    }

    private record PendingHybridPlugin(Path pluginJar, URLClassLoader loader, HybridPluginDescriptor descriptor) {
    }

    /**
     * Stage A: scans plugin directories for hybrid plugin JARs, initializes the Mixin/Quilt
     * framework (but does not yet register any mixin configs — see the class javadoc for why), and
     * unconditionally installs the {@link LaminateMixinTransformer} gate that will trigger Stage B
     * later — every boot, regardless of whether any hybrid plugin was discovered, since
     * {@code laminate-core-mixins}' own always-on config ({@link #CORE_MIXINS_CONFIG}) always needs
     * Stage B to register it too.
     *
     * <p>This method is idempotent — subsequent calls, regardless of arguments, return the cached
     * result from the first call without re-scanning or touching the Mixin/Quilt machinery again.
     *
     * <p>Queuing {@code laminate-core-mixins}' config unconditionally (and, with it, always
     * initializing the Mixin/Quilt framework and always installing the gate transformer) is a
     * deliberate change from the pre-phase-4 behavior, which only did any of this when at least one
     * hybrid plugin was discovered — see {@code docs/plans/phase-4-core-mixins.md}, Global
     * Constraints' "Registration" section, for the full rationale.
     *
     * @param instrumentation  the JVM {@link Instrumentation} handle captured at agent load time
     * @param defaultPluginDir the primary plugins directory
     * @param addPluginFiles   additional individual plugin JAR files
     * @param addPluginDirs    additional directories whose JARs should be scanned
     * @return lower-case names of plugins whose descriptor could not be read in Stage A (this does
     *     <em>not</em> include plugins that fail later, in Stage B — consult {@link LaminateBridge}
     *     once Stage B has run for the complete picture)
     */
    public static Set<String> bootstrap(final Instrumentation instrumentation,
                                         final Path defaultPluginDir,
                                         final List<Path> addPluginFiles,
                                         final List<Path> addPluginDirs) {
        if (cachedFailedPlugins != null) {
            return cachedFailedPlugins;
        }

        final Set<String> failedPlugins = scanPluginJars(defaultPluginDir, addPluginFiles, addPluginDirs);
        ensureMixinBootstrap();
        initializeStageAContext(instrumentation);

        if (!failedPlugins.isEmpty()) {
            LOGGER.log(Level.WARNING, String.format(
                    LaminateLogMessages.BOOTSTRAP_FAILED_SUMMARY, failedPlugins.size()));
        }

        cachedFailedPlugins = Set.copyOf(failedPlugins);

        return cachedFailedPlugins;
    }

    private static Set<String> scanPluginJars(final Path defaultPluginDir,
                                              final List<Path> addPluginFiles,
                                              final List<Path> addPluginDirs) {
        final Set<Path> pluginJars = PluginJarCollector.collectPluginJars(
                defaultPluginDir, addPluginFiles, addPluginDirs);
        final Set<String> failedPlugins = new HashSet<>();
        for (final Path pluginJar : pluginJars) {
            processPluginJar(pluginJar, failedPlugins);
        }
        return failedPlugins;
    }

    private static void initializeStageAContext(final Instrumentation instrumentation) {
        LaminateRuntimeBootstrap.instrumentation = instrumentation;
        excludedTriggerLoader = Thread.currentThread().getContextClassLoader();
        excludedBundlerLoader = excludedTriggerLoader == null ? null : excludedTriggerLoader.getParent();
        installGateTransformer(instrumentation);
        spawnStageBWatchdog(PENDING_HYBRID_PLUGINS.size());
    }

    static void registerPluginClassLoader(final URLClassLoader loader) {
        if (launcher != null) {
            launcher.registerPluginClassLoader(loader);
        }
    }

    private static void processPluginJar(final Path pluginJar, final Set<String> failedPlugins) {
        HybridPluginDescriptor descriptor = null;
        try {
            descriptor = HybridPluginDescriptorReader.read(pluginJar);
            if (descriptor == null) {
                return;
            }
            registerPendingPlugin(pluginJar, descriptor);
        } catch (final Throwable throwable) {
            final String pluginName = descriptor == null
                    ? pluginJar.getFileName().toString()
                    : descriptor.name();
            LOGGER.log(Level.WARNING, String.format(
                    LaminateLogMessages.HYBRID_PLUGIN_BOOTSTRAP_FAILED, pluginName, pluginJar), throwable);
            failedPlugins.add(pluginName.toLowerCase(Locale.ROOT));
        }
    }

    private static void registerPendingPlugin(final Path pluginJar, final HybridPluginDescriptor descriptor)
           throws IOException {
        ensureMixinBootstrap();
        final URL pluginUrl = pluginJar.toUri().toURL();

        final URLClassLoader mixinResourceLoader = new URLClassLoader(
                new URL[]{pluginUrl}, Thread.currentThread().getContextClassLoader());

        PINNED_MIXIN_LOADERS.add(mixinResourceLoader);
        registerPluginClassLoader(mixinResourceLoader);

        PENDING_HYBRID_PLUGINS.add(new PendingHybridPlugin(pluginJar, mixinResourceLoader, descriptor));
        LOGGER.log(Level.INFO, String.format(LaminateLogMessages.DISCOVERED_HYBRID_PLUGIN,
                descriptor.name(), descriptor.mixinConfigs().size()));
    }

    private static void registerMixinConfigs(final URLClassLoader loader, final HybridPluginDescriptor descriptor) {
        final Thread thread = Thread.currentThread();
        final ClassLoader previous = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(loader);
            for (final String config : descriptor.mixinConfigs()) {
                Mixins.addConfiguration(config);
            }
            LOGGER.log(Level.INFO, String.format(LaminateLogMessages.INJECTED_MIXIN_CONFIGS,
                    descriptor.mixinConfigs().size(), descriptor.name()));
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static void registerCoreMixinsConfig() {
        try {
            Mixins.addConfiguration(CORE_MIXINS_CONFIG);
            LOGGER.log(Level.INFO, String.format(
                    LaminateLogMessages.INJECTED_CORE_MIXINS_CONFIG, CORE_MIXINS_CONFIG));
        } catch (final Throwable throwable) {
            LOGGER.log(Level.WARNING, String.format(
                    LaminateLogMessages.FAILED_CORE_MIXINS_CONFIG, CORE_MIXINS_CONFIG), throwable);
        }
    }

    private static synchronized void ensureMixinBootstrap() {
        if (mixinBootstrapped) {
            return;
        }
        launcher = new LaminateQuiltLauncher();
        System.setProperty("mixin.service", MixinServiceKnot.class.getName());
        MixinBootstrap.init();
        MixinExtrasBootstrap.init();

        mixinBootstrapped = true;
    }

    private static void installGateTransformer(final Instrumentation instrumentation) {
        if (instrumentation == null) {
            LOGGER.log(Level.WARNING, LaminateLogMessages.NO_INSTRUMENTATION);
            return;
        }

        final LaminateMixinTransformer transformer = new LaminateMixinTransformer();
        instrumentation.addTransformer(transformer, true);
        LOGGER.log(Level.INFO, LaminateLogMessages.INSTALLED_TRANSFORMER);
    }

    private static final long STAGE_B_WATCHDOG_DELAY_SECONDS = 60;

    private static void spawnStageBWatchdog(final int pluginCount) {
        final Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(STAGE_B_WATCHDOG_DELAY_SECONDS * 1000L);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (stageBTransformer == null) {
                LOGGER.log(Level.ERROR, String.format(LaminateLogMessages.WATCHDOG_TIMEOUT,
                        STAGE_B_WATCHDOG_DELAY_SECONDS, pluginCount));
            }
        }, "laminate-stage-b-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    static IMixinTransformer transformerForGameLoad(final ClassLoader loader) {
        final IMixinTransformer ready = stageBTransformer;
        if (ready != null) {
            return ready;
        }
        if (stageBAttempted) {
            return null;
        }
        if (!isGameLoaderCandidate(loader)) {
            return null;
        }
        return triggerStageB(loader);
    }

    private static boolean isGameLoaderCandidate(final ClassLoader loader) {
        if (!(loader instanceof URLClassLoader)) {
            return false;
        }
        if (loader == excludedBundlerLoader) {
            return false;
        }
        if (loader == excludedTriggerLoader) {
            return false;
        }
        return !PINNED_MIXIN_LOADERS.contains(loader);
    }

    private static synchronized IMixinTransformer triggerStageB(final ClassLoader triggeringLoader) {
        if (stageBAttempted) {
            return stageBTransformer;
        }
        stageBAttempted = true;

        LaminateQuiltLauncher.pinGameLoaderIfAbsent(triggeringLoader);

        final Set<String> stageBFailedPlugins = registerPendingMixins();

        finishMixinPhases();
        final IMixinTransformer transformer = obtainMixinTransformer();
        stageBTransformer = transformer;

        if (transformer != null && instrumentation != null) {
            retransformAlreadyLoadedClasses(instrumentation);
        }

        final Set<String> loadedResult = Set.copyOf(LOADED_HYBRID_PLUGIN_NAMES);
        final Set<String> allFailedPlugins = new HashSet<>(cachedFailedPlugins == null ? Set.of() : cachedFailedPlugins);
        allFailedPlugins.addAll(stageBFailedPlugins);
        final Set<String> failedResult = Set.copyOf(allFailedPlugins);
        LaminateBridge.recordResult(loadedResult, failedResult);
        publishToBundlerNamespaceBridge(loadedResult, failedResult);

        if (!stageBFailedPlugins.isEmpty()) {
            LOGGER.log(Level.WARNING, String.format(
                    LaminateLogMessages.BOOTSTRAP_FAILED_SUMMARY, stageBFailedPlugins.size()));
        }

        LOGGER.log(Level.INFO, String.format(LaminateLogMessages.MIXIN_TRANSFORMATIONS_ACTIVE,
                LOADED_HYBRID_PLUGIN_NAMES.size(), allFailedPlugins.size()));

        return stageBTransformer;
    }

    private static Set<String> registerPendingMixins() {
        registerCoreMixinsConfig();

        final Set<String> stageBFailedPlugins = new HashSet<>();
        for (final PendingHybridPlugin pending : PENDING_HYBRID_PLUGINS) {
            try {
                registerMixinConfigs(pending.loader(), pending.descriptor());
                LOADED_HYBRID_PLUGIN_NAMES.add(pending.descriptor().name());
            } catch (final Throwable throwable) {
                LOGGER.log(Level.WARNING, String.format(LaminateLogMessages.HYBRID_PLUGIN_BOOTSTRAP_FAILED,
                        pending.descriptor().name(), pending.pluginJar()), throwable);
                stageBFailedPlugins.add(pending.descriptor().name().toLowerCase(Locale.ROOT));
            }
        }
        return stageBFailedPlugins;
    }

    private static void publishToBundlerNamespaceBridge(final Set<String> loaded, final Set<String> failed) {
        if (excludedBundlerLoader == null) {
            return;
        }
        try {
            final Class<?> bridgeClass = Class.forName(
                    "eu.lschreiber.laminate.bridge.LaminateBridge", true, excludedBundlerLoader);
            if (bridgeClass == LaminateBridge.class) {
                return;
            }
            final Method recordResult = bridgeClass.getMethod("recordResult", Set.class, Set.class);
            recordResult.invoke(null, loaded, failed);
            LOGGER.log(Level.DEBUG, LaminateLogMessages.REPUBLISHED_BRIDGE);
        } catch (final Throwable throwable) {
            LOGGER.log(Level.DEBUG, LaminateLogMessages.REPUBLISH_FAILED, throwable);
        }
    }

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

    private static IMixinTransformer obtainMixinTransformer() {
        try {
            final Method getTransformer = MixinServiceKnot.class.getDeclaredMethod("getTransformer");
            getTransformer.setAccessible(true);
            final IMixinTransformer transformer = (IMixinTransformer) getTransformer.invoke(null);
            if (transformer == null) {
                LOGGER.log(Level.ERROR, LaminateLogMessages.OBTAIN_TRANSFORMER_NULL);
            }
            return transformer;
        } catch (final ReflectiveOperationException e) {
            LOGGER.log(Level.ERROR, LaminateLogMessages.OBTAIN_TRANSFORMER_FAILED, e);
            return null;
        }
    }

    private static void retransformAlreadyLoadedClasses(final Instrumentation instrumentation) {
        final List<Class<?>> toRetransform = findRetransformableClasses(instrumentation);
        if (toRetransform.isEmpty()) {
            return;
        }

        LOGGER.log(Level.INFO, String.format(LaminateLogMessages.RETRANSFORMING, toRetransform.size()));

        int transformed = 0;
        for (final Class<?> clazz : toRetransform) {
            if (tryRetransform(instrumentation, clazz)) {
                transformed++;
            }
        }

        if (transformed > 0) {
            LOGGER.log(Level.INFO, String.format(LaminateLogMessages.RETRANSFORMED, transformed));
        }
    }

    private static List<Class<?>> findRetransformableClasses(final Instrumentation instrumentation) {
        final Class<?>[] allLoaded = instrumentation.getAllLoadedClasses();
        final List<Class<?>> result = new ArrayList<>();
        for (final Class<?> clazz : allLoaded) {
            final String name = clazz.getName();
            if ((name.startsWith("net.minecraft.") || name.startsWith("io.papermc.")
                    || name.startsWith("ca.spottedleaf."))
                    && instrumentation.isModifiableClass(clazz)) {
                result.add(clazz);
            }
        }
        return result;
    }

    private static boolean tryRetransform(final Instrumentation instrumentation, final Class<?> clazz) {
        try {
            instrumentation.retransformClasses(clazz);
            return true;
        } catch (final UnsupportedOperationException e) {
            LOGGER.log(Level.WARNING, String.format(
                    LaminateLogMessages.RETRANSFORM_UNSUPPORTED, clazz.getName()));
        } catch (final Exception e) {
            LOGGER.log(Level.WARNING, String.format(LaminateLogMessages.RETRANSFORM_FAILED, clazz.getName()), e);
        }
        return false;
    }
}
