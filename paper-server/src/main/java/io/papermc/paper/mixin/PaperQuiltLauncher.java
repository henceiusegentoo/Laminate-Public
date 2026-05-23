package io.papermc.paper.mixin;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.Manifest;
import net.fabricmc.api.EnvType;
import org.quiltmc.loader.api.ModContainer;
import org.quiltmc.loader.impl.entrypoint.GameTransformer;
import org.quiltmc.loader.impl.game.MappingConfiguration;
import org.quiltmc.loader.impl.launch.common.QuiltLauncherBase;

/**
 * A {@link org.quiltmc.loader.impl.launch.common.QuiltLauncher QuiltLauncher}
 * implementation that bridges Paper's runtime into the Quilt/Knot mixin
 * environment.
 *
 * <h2>Comparison with Quilt's Knot</h2>
 * <ul>
 *   <li><strong>Class bytes</strong> — Knot reads raw bytes via
 *       {@code KnotClassDelegate}; this launcher reads from the server class
 *       loader and registered plugin class loaders.</li>
 *   <li><strong>isClassLoaded</strong> — returns {@code false} to prevent
 *       {@code MixinTargetAlreadyLoadedException}; retransformation handles
 *       classes that were loaded before the transformer was installed.</li>
 *   <li><strong>Classpath</strong> — Knot dynamically adds paths to its class
 *       loader; this launcher tracks registered plugin class loaders.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * <p>{@link #pluginClassLoaders} is a {@link CopyOnWriteArrayList}: write-rare
 * during bootstrap, read-often from the Mixin transformer thread.</p>
 *
 * @see HybridPluginMixinBootstrap
 * @see PaperMixinClassFileTransformer
 */
final class PaperQuiltLauncher extends QuiltLauncherBase {

    private static final GameTransformer EMPTY_TRANSFORMER = new GameTransformer();

    private final ClassLoader classLoader;
    private final List<URLClassLoader> pluginClassLoaders = new CopyOnWriteArrayList<>();
    private final List<Path> classPathEntries = new CopyOnWriteArrayList<>();

    /**
     * Creates a new launcher and registers it as the global Quilt launcher.
     *
     * <p>The {@link QuiltLauncherBase} super-constructor calls
     * {@code setLauncher(this)}, making this instance visible to
     * {@code MixinServiceKnot}.</p>
     */
    PaperQuiltLauncher() {
        this.classLoader = getClass().getClassLoader();
        setProperties(new HashMap<>());
    }

    /**
     * Registers a plugin-specific {@link URLClassLoader} so the Mixin
     * transformer can resolve mixin configs and classes from the plugin JAR.
     */
    void registerPluginClassLoader(final URLClassLoader loader) {
        pluginClassLoaders.add(loader);
    }

    @Override
    public EnvType getEnvironmentType() {
        return EnvType.SERVER;
    }

    @Override
    public boolean isDevelopment() {
        return false;
    }

    @Override
    public String getEntrypoint() {
        return "";
    }

    /**
     * {@return {@code null} — Paper ships Mojang-mapped; no runtime remapping}
     */
    @Override
    public MappingConfiguration getMappingConfiguration() {
        return null;
    }

    /**
     * {@return {@code "mojang"} — hybrid plugin mixins use Mojang-mapped names}
     */
    @Override
    public String getTargetNamespace() {
        return "mojang";
    }

    @Override
    public ClassLoader getTargetClassLoader() {
        return classLoader;
    }

    @Override
    public ClassLoader getClassLoader(final ModContainer mod) {
        return classLoader;
    }

    /**
     * Always returns {@code false} to prevent
     * {@code MixinTargetAlreadyLoadedException} during config preparation.
     *
     * <p>In Quilt's Knot this honestly probes {@code findLoadedClass()}, but
     * Paper's instrumentation-based approach handles already-loaded classes via
     * {@link java.lang.instrument.Instrumentation#retransformClasses}.</p>
     */
    @Override
    public boolean isClassLoaded(final String name) {
        return false;
    }

    @Override
    public Class<?> loadIntoTarget(final String name) throws ClassNotFoundException {
        return Class.forName(name, true, classLoader);
    }

    /**
     * Reads the raw bytecode for the named class from the server and plugin
     * class loaders.
     *
     * <p>Paper has no pre-mixin game transformers, so the
     * {@code runTransformers} parameter is ignored.</p>
     */
    @Override
    public byte[] getClassByteArray(final String name,
                                    final boolean runTransformers) throws IOException {
        final String resourcePath = name.replace('.', '/') + ".class";
        final InputStream in = findResource(resourcePath);
        if (in == null) {
            return null;
        }
        try (in) {
            return in.readAllBytes();
        }
    }

    @Override
    public InputStream getResourceAsStream(final String name) {
        return findResource(name);
    }

    @Override
    public URL getResourceURL(final String name) {
        return findResourceUrl(name);
    }

    @Override
    public Manifest getManifest(final Path originPath) {
        return null;
    }

    @Override
    public GameTransformer getEntrypointTransformer() {
        return EMPTY_TRANSFORMER;
    }

    @Override
    public List<Path> getClassPath() {
        return Collections.unmodifiableList(classPathEntries);
    }

    @Override
    public void addToClassPath(final Path path, final String... allowedPrefixes) {
        classPathEntries.add(path);
    }

    @Override
    public void addToClassPath(final Path path, final ModContainer mod,
                               final URL origin, final String... allowedPrefixes) {
        classPathEntries.add(path);
    }

    @Override
    public void setAllowedPrefixes(final Path path, final String... prefixes) {
        // Not enforced in Paper's class loader hierarchy.
    }

    @Override
    public void setTransformCache(final URL insideTransformCache) {
        // Paper does not use Quilt's transform caching.
    }

    @Override
    public void setHiddenClasses(final Set<String> classes) {
        // Paper does not enforce class hiding.
    }

    @Override
    public void setHiddenClasses(final Map<String, String> classes) {
        // Paper does not enforce class hiding.
    }

    @Override
    public void setPluginPackages(final Map<String, ClassLoader> hiddenClasses) {
        // Paper does not enforce plugin-package isolation.
    }

    @Override
    public void hideParentUrl(final URL hidden) {
        // Paper does not hide parent URLs.
    }

    @Override
    public void hideParentPath(final Path obf) {
        // Paper does not hide parent paths.
    }

    @Override
    public void validateGameClassLoader(final Object gameInstance) {
        // Paper manages its own class loading; Knot-style validation is not applicable.
    }

    /**
     * Searches all available class loaders for an {@link InputStream} matching
     * the given resource name.
     *
     * <p>Search order: server class loader → thread-context class loader →
     * registered plugin class loaders.</p>
     *
     * @return an open input stream, or {@code null} if not found
     */
    private InputStream findResource(final String name) {
        final InputStream fromServer = classLoader.getResourceAsStream(name);
        if (fromServer != null) {
            return fromServer;
        }

        final ClassLoader ctx = contextClassLoaderIfDistinct();
        if (ctx != null) {
            final InputStream fromContext = ctx.getResourceAsStream(name);
            if (fromContext != null) {
                return fromContext;
            }
        }

        for (final URLClassLoader pluginCl : pluginClassLoaders) {
            final InputStream fromPlugin = pluginCl.getResourceAsStream(name);
            if (fromPlugin != null) {
                return fromPlugin;
            }
        }

        return null;
    }

    /**
     * Searches all available class loaders for a {@link URL} matching
     * the given resource name.
     *
     * <p>Search order: server class loader → thread-context class loader →
     * registered plugin class loaders.</p>
     *
     * @return the resource URL, or {@code null} if not found
     */
    private URL findResourceUrl(final String name) {
        final URL fromServer = classLoader.getResource(name);
        if (fromServer != null) {
            return fromServer;
        }

        final ClassLoader ctx = contextClassLoaderIfDistinct();
        if (ctx != null) {
            final URL fromContext = ctx.getResource(name);
            if (fromContext != null) {
                return fromContext;
            }
        }

        for (final URLClassLoader pluginCl : pluginClassLoaders) {
            final URL fromPlugin = pluginCl.getResource(name);
            if (fromPlugin != null) {
                return fromPlugin;
            }
        }

        return null;
    }

    /**
     * Returns the thread-context class loader if it differs from the server
     * class loader, or {@code null} otherwise. Eliminates a repeated check
     * across {@link #findResource} and {@link #findResourceUrl}.
     */
    private ClassLoader contextClassLoaderIfDistinct() {
        final ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        return (ctx != null && ctx != classLoader) ? ctx : null;
    }
}
