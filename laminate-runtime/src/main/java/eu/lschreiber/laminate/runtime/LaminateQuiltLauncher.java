package eu.lschreiber.laminate.runtime;

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
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.Manifest;
import net.fabricmc.api.EnvType;
import org.quiltmc.loader.api.ModContainer;
import org.quiltmc.loader.impl.entrypoint.GameTransformer;
import org.quiltmc.loader.impl.game.MappingConfiguration;
import org.quiltmc.loader.impl.launch.common.QuiltLauncherBase;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;

/** Quilt launcher that resolves game classes from the pinned loader and registered plugins. */
final class LaminateQuiltLauncher extends QuiltLauncherBase {

    private static final GameTransformer EMPTY_TRANSFORMER = new GameTransformer();

    private static final AtomicReference<ClassLoader> pinnedGameLoader = new AtomicReference<>();

    private final ClassLoader classLoader;
    private final List<URLClassLoader> pluginClassLoaders = new CopyOnWriteArrayList<>();
    private final List<Path> classPathEntries = new CopyOnWriteArrayList<>();

    private final ClassLoader targetClassLoaderView;

    LaminateQuiltLauncher() {
        this.classLoader = getClass().getClassLoader();
        this.targetClassLoaderView = new CombiningClassLoader(
                IMixinConfigPlugin.class.getClassLoader(),
                List.of(this::pinnedGameLoaderIfDistinct, () -> this.classLoader,
                        () -> Thread.currentThread().getContextClassLoader()),
                this.pluginClassLoaders);
        setProperties(new HashMap<>());
    }

    static void pinGameLoaderIfAbsent(final ClassLoader loader) {
        if (loader != null && pinnedGameLoader.get() == null) {
            pinnedGameLoader.compareAndSet(null, loader);
        }
    }

    static ClassLoader pinnedGameLoader() {
        return pinnedGameLoader.get();
    }

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

    @Override
    public MappingConfiguration getMappingConfiguration() {
        return null;
    }

    @Override
    public String getTargetNamespace() {
        return "mojang";
    }

    @Override
    public ClassLoader getTargetClassLoader() {
        return targetClassLoaderView;
    }

    @Override
    public ClassLoader getClassLoader(final ModContainer mod) {
        return classLoader;
    }

    @Override
    public boolean isClassLoaded(final String name) {
        return false;
    }

    @Override
    public Class<?> loadIntoTarget(final String name) throws ClassNotFoundException {
        return Class.forName(name, true, classLoader);
    }

    @Override
    public byte[] getClassByteArray(final String name, final boolean runTransformers) throws IOException {
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
    public void addToClassPath(final Path path, final ModContainer mod, final URL origin,
                                final String... allowedPrefixes) {
        classPathEntries.add(path);
    }

    @Override
    public void setAllowedPrefixes(final Path path, final String... prefixes) {
    }

    @Override
    public void setTransformCache(final URL insideTransformCache) {
    }

    @Override
    public void setHiddenClasses(final Set<String> classes) {
    }

    @Override
    public void setHiddenClasses(final Map<String, String> classes) {
    }

    @Override
    public void setPluginPackages(final Map<String, ClassLoader> hiddenClasses) {
    }

    @Override
    public void hideParentUrl(final URL hidden) {
    }

    @Override
    public void hideParentPath(final Path obf) {
    }

    @Override
    public void validateGameClassLoader(final Object gameInstance) {
    }

    private InputStream findResource(final String name) {
        return findFirstResource(name, ClassLoader::getResourceAsStream);
    }

    private URL findResourceUrl(final String name) {
        return findFirstResource(name, ClassLoader::getResource);
    }

    private <T> T findFirstResource(final String name, final ResourceLookup<T> lookup) {
        final ClassLoader pinned = pinnedGameLoaderIfDistinct();
        if (pinned != null) {
            final T fromPinned = lookup.lookup(pinned, name);
            if (fromPinned != null) {
                return fromPinned;
            }
        }

        final T fromServer = lookup.lookup(classLoader, name);
        if (fromServer != null) {
            return fromServer;
        }

        final ClassLoader ctx = contextClassLoaderIfDistinct(pinned);
        if (ctx != null) {
            final T fromContext = lookup.lookup(ctx, name);
            if (fromContext != null) {
                return fromContext;
            }
        }

        for (final URLClassLoader pluginCl : pluginClassLoaders) {
            final T fromPlugin = lookup.lookup(pluginCl, name);
            if (fromPlugin != null) {
                return fromPlugin;
            }
        }

        return null;
    }

    private ClassLoader pinnedGameLoaderIfDistinct() {
        final ClassLoader pinned = pinnedGameLoader.get();
        return (pinned != null && pinned != classLoader) ? pinned : null;
    }

    private ClassLoader contextClassLoaderIfDistinct(final ClassLoader alreadySearchedPinned) {
        final ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        if (ctx == null || ctx == classLoader || ctx == alreadySearchedPinned) {
            return null;
        }
        return ctx;
    }

    @FunctionalInterface
    private interface ResourceLookup<T> {
        T lookup(ClassLoader loader, String name);
    }
}
