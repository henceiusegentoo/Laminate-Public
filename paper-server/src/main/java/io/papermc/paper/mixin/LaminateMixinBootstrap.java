package io.papermc.paper.mixin;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Public entry point for the Laminate hybrid mixin system.
 *
 * <p>This class exposes a single method ({@link #bootstrap}) that should be
 * called <strong>before any Minecraft class is loaded</strong>. It scans
 * plugin JARs for {@code hybrid: true} descriptors, initializes the
 * Quilt/Knot mixin environment, and installs a JVM-wide
 * {@link java.lang.instrument.ClassFileTransformer} that applies mixin
 * bytecode modifications to every class loaded thereafter.</p>
 *
 * <p>All implementation details (launcher, agent, transformer) are
 * package-private and hidden behind this façade.</p>
 */
public final class LaminateMixinBootstrap {

    private LaminateMixinBootstrap() {
    }

    /**
     * Discovers hybrid plugins and sets up the mixin transformation pipeline.
     *
     * <p>This method is idempotent — subsequent calls return the cached result
     * of the first invocation.</p>
     *
     * @param pluginDir    the primary plugins directory (typically {@code plugins/})
     * @param addFiles     additional individual plugin JAR files
     * @param addDirs      additional directories whose JARs should be scanned
     * @return lower-case names of plugins whose mixin bootstrap failed;
     *         these plugins should be disabled by the caller
     */
    public static Set<String> bootstrap(Path pluginDir, List<Path> addFiles, List<Path> addDirs) {
        return HybridPluginMixinBootstrap.injectHybridMixins(pluginDir, addFiles, addDirs);
    }

    /**
     * Returns the names (original case, as declared in {@code plugin.yml}) of
     * hybrid plugins whose mixin configs were successfully injected during
     * bootstrap.
     *
     * @return unmodifiable set of successfully bootstrapped hybrid plugin names
     */
    public static Set<String> getLoadedHybridPluginNames() {
        return HybridPluginMixinBootstrap.getLoadedHybridPluginNames();
    }
}
