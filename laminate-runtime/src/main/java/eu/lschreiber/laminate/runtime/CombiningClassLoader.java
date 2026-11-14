package eu.lschreiber.laminate.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** Class loader that tries shared loaders first, then plugin JARs. */
final class CombiningClassLoader extends ClassLoader {

    private final List<Supplier<ClassLoader>> singleCandidates;
    private final List<? extends ClassLoader> pluginLoaders;

    CombiningClassLoader(final ClassLoader parent, final List<Supplier<ClassLoader>> singleCandidates, final List<? extends ClassLoader> pluginLoaders) {
        super(parent);
        this.singleCandidates = singleCandidates;
        this.pluginLoaders = pluginLoaders;
    }

    @Override
    protected Class<?> findClass(final String name) throws ClassNotFoundException {
        final Set<ClassLoader> tried = new HashSet<>();

        for (final Supplier<ClassLoader> supplier : this.singleCandidates) {
            final ClassLoader candidate = supplier.get();
            if (candidate == null || !tried.add(candidate)) {
                continue;
            }
            try {
                return candidate.loadClass(name);
            } catch (final ClassNotFoundException ignored) {
                // try the next candidate
            }
        }

        final String resourcePath = name.replace('.', '/') + ".class";
        for (final ClassLoader pluginLoader : this.pluginLoaders) {
            if (!tried.add(pluginLoader)) {
                continue;
            }
            final byte[] bytes = readClassBytes(pluginLoader, resourcePath);
            if (bytes != null) {
                return defineClass(name, bytes, 0, bytes.length);
            }
        }

        throw new ClassNotFoundException(name);
    }

    private static byte[] readClassBytes(final ClassLoader loader, final String resourcePath) {
        try (InputStream in = loader.getResourceAsStream(resourcePath)) {
            return in == null ? null : in.readAllBytes();
        } catch (final IOException ignored) {
            return null;
        }
    }
}
