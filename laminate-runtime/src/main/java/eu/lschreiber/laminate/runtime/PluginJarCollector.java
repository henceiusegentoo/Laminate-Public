package eu.lschreiber.laminate.runtime;

import eu.lschreiber.laminate.bridge.LaminateLog;
import eu.lschreiber.laminate.bridge.LaminateLog.Level;
import eu.lschreiber.laminate.bridge.LaminateLogMessages;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Collects candidate plugin JARs for hybrid descriptor scanning. */
final class PluginJarCollector {

    private static final LaminateLog LOGGER = LaminateLog.getLogger("laminate.runtime.PluginJarCollector");

    private PluginJarCollector() {
    }

    static Set<Path> collectPluginJars(final Path defaultPluginDir,
                                        final List<Path> additionalFiles,
                                        final List<Path> additionalDirs) {
        final Set<Path> jars = new HashSet<>();
        collectFromDirectory(defaultPluginDir, jars);
        for (final Path file : additionalFiles) {
            addIfJar(file, jars);
        }
        for (final Path dir : additionalDirs) {
            collectFromDirectory(dir, jars);
        }
        return jars;
    }

    private static void collectFromDirectory(final Path directory, final Set<Path> out) {
        if (directory == null || !Files.isDirectory(directory)) {
            return;
        }
        try (java.util.stream.Stream<Path> stream = Files.list(directory)) {
            stream.filter(PluginJarCollector::isJar)
                    .map(path -> path.toAbsolutePath().normalize())
                    .forEach(out::add);
        } catch (final IOException e) {
            LOGGER.log(Level.WARNING,
                    String.format(LaminateLogMessages.FAILED_LIST_PLUGIN_DIR, directory), e);
        }
    }

    private static void addIfJar(final Path path, final Set<Path> out) {
        if (isJar(path)) {
            out.add(path.toAbsolutePath().normalize());
        }
    }

    private static boolean isJar(final Path path) {
        return path != null
                && Files.isRegularFile(path)
                && path.getFileName().toString().endsWith(".jar");
    }
}
