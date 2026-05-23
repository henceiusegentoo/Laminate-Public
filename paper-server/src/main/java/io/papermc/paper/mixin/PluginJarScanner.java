package io.papermc.paper.mixin;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;

/**
 * Scans plugin directories and collects JAR paths that may contain hybrid
 * plugin descriptors.
 *
 * <p>Extracted from {@link HybridPluginMixinBootstrap} to follow the Single
 * Responsibility Principle: this class only deals with filesystem discovery,
 * not mixin registration or environment setup.</p>
 *
 * @see HybridPluginMixinBootstrap
 */
final class PluginJarScanner {

    private static final Logger LOGGER = LogUtils.getClassLogger();

    private PluginJarScanner() {
    }

    /**
     * Collects all plugin JAR paths from the default directory, additional
     * files, and additional directories into a single de-duplicated set.
     *
     * <p>All returned paths are normalized and absolute.</p>
     *
     * @param defaultPluginDir the primary plugins directory
     * @param additionalFiles  individual JAR files to include
     * @param additionalDirs   directories whose JARs should be scanned
     * @return a normalized, absolute set of JAR paths
     */
    static Set<Path> collectPluginJars(final Path defaultPluginDir,
                                       final List<Path> additionalFiles,
                                       final List<Path> additionalDirs) {
        final Set<Path> jars = new HashSet<>();
        collectFromDirectory(defaultPluginDir, jars);
        for (final Path file : additionalFiles) {
            if (isJar(file)) {
                jars.add(file.toAbsolutePath().normalize());
            }
        }
        for (final Path dir : additionalDirs) {
            collectFromDirectory(dir, jars);
        }
        return jars;
    }

    /**
     * Lists all {@code .jar} files in the given directory and adds their
     * normalized, absolute paths to the output set. If the path is {@code null}
     * or not a directory the call is a no-op. An {@link IOException} during
     * listing is logged as a warning.
     */
    private static void collectFromDirectory(final Path directory,
                                             final Set<Path> out) {
        if (directory == null || !Files.isDirectory(directory)) {
            return;
        }
        try (final var stream = Files.list(directory)) {
            stream.filter(PluginJarScanner::isJar)
                    .map(path -> path.toAbsolutePath().normalize())
                    .forEach(out::add);
        } catch (final IOException e) {
            LOGGER.warn("Failed to list plugin directory {} for hybrid mixin discovery.", directory, e);
        }
    }

    /**
     * Tests whether the given path points to an existing regular file with a
     * {@code .jar} extension.
     */
    private static boolean isJar(final Path path) {
        return path != null
                && Files.isRegularFile(path)
                && path.getFileName().toString().endsWith(".jar");
    }
}
