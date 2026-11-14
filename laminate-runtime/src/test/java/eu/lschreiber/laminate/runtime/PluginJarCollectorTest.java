package eu.lschreiber.laminate.runtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link PluginJarCollector#collectPluginJars(Path, List, List)} against a real
 * directory tree built in a per-test temp directory, matching the fork's
 * {@code PluginJarScanner} scan/normalize/dedupe/filter semantics.
 */
@DisplayName("PluginJarCollector")
class PluginJarCollectorTest {

    @TempDir
    Path tempDir;

    private Path defaultPluginDir;
    private Path aJar;
    private Path bJar;

    @BeforeEach
    void setUpDefaultPluginDir() throws IOException {
        defaultPluginDir = tempDir.resolve("plugins");
        Files.createDirectories(defaultPluginDir);

        aJar = createFile(defaultPluginDir.resolve("a.jar"));
        bJar = createFile(defaultPluginDir.resolve("b.jar"));
        createFile(defaultPluginDir.resolve("readme.txt"));
        createFile(defaultPluginDir.resolve("not-a-jar.jarx"));
        Files.createDirectories(defaultPluginDir.resolve("nested-dir-should-not-be-scanned"));
    }

    @Test
    @DisplayName("scans only .jar regular files directly inside the default plugin directory")
    void scansDefaultDirectoryJarsOnly() {
        final Set<Path> result = PluginJarCollector.collectPluginJars(defaultPluginDir, List.of(), List.of());

        assertThat(result).containsExactlyInAnyOrder(
                aJar.toAbsolutePath().normalize(),
                bJar.toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("returned paths are absolute and normalized even when constructed with redundant segments")
    void resultsAreAbsoluteAndNormalized() {
        final Path messyDefaultDir = defaultPluginDir.resolve(".").resolve("..").resolve("plugins");

        final Set<Path> result = PluginJarCollector.collectPluginJars(messyDefaultDir, List.of(), List.of());

        for (final Path path : result) {
            assertThat(path).isAbsolute();
            assertThat(path).isEqualTo(path.normalize());
        }
        assertThat(result).containsExactlyInAnyOrder(
                aJar.toAbsolutePath().normalize(),
                bJar.toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("an explicit extra .jar file outside the default directory is included")
    void includesExplicitExtraFile() throws IOException {
        final Path extraJar = createFile(tempDir.resolve("standalone-extra.jar"));

        final Set<Path> result = PluginJarCollector.collectPluginJars(
                defaultPluginDir, List.of(extraJar), List.of());

        assertThat(result).containsExactlyInAnyOrder(
                aJar.toAbsolutePath().normalize(),
                bJar.toAbsolutePath().normalize(),
                extraJar.toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("an explicit extra non-.jar file is filtered out")
    void filtersOutExplicitNonJarFile() throws IOException {
        final Path extraTxt = createFile(tempDir.resolve("standalone-extra.txt"));

        final Set<Path> result = PluginJarCollector.collectPluginJars(
                defaultPluginDir, List.of(extraTxt), List.of());

        assertThat(result).containsExactlyInAnyOrder(
                aJar.toAbsolutePath().normalize(),
                bJar.toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("an explicit extra directory has its .jar files scanned and included")
    void scansExplicitExtraDirectory() throws IOException {
        final Path extraDir = tempDir.resolve("extra-dir");
        Files.createDirectories(extraDir);
        final Path cJar = createFile(extraDir.resolve("c.jar"));
        createFile(extraDir.resolve("d.txt"));

        final Set<Path> result = PluginJarCollector.collectPluginJars(
                defaultPluginDir, List.of(), List.of(extraDir));

        assertThat(result).containsExactlyInAnyOrder(
                aJar.toAbsolutePath().normalize(),
                bJar.toAbsolutePath().normalize(),
                cJar.toAbsolutePath().normalize());
    }

    /**
     * {@code aJar} is already picked up by the default directory scan; it is also passed as an
     * explicit extra file, but spelled with a redundant {@code "./"} segment, to prove that
     * normalization - not just object identity - drives the dedup.
     */
    @Test
    @DisplayName("the same jar reachable via both the default dir scan and an explicit file is de-duplicated")
    void deduplicatesJarSeenTwice() {
        final Path sameJarDifferentSpelling = defaultPluginDir.resolve(".").resolve("a.jar");

        final Set<Path> result = PluginJarCollector.collectPluginJars(
                defaultPluginDir, List.of(sameJarDifferentSpelling), List.of());

        assertThat(result).containsExactlyInAnyOrder(
                aJar.toAbsolutePath().normalize(),
                bJar.toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("the same jar reachable via both an explicit dir scan and an explicit file is de-duplicated")
    void deduplicatesJarSeenViaExplicitDirAndExplicitFile() throws IOException {
        final Path extraDir = tempDir.resolve("extra-dir");
        Files.createDirectories(extraDir);
        final Path cJar = createFile(extraDir.resolve("c.jar"));

        final Set<Path> result = PluginJarCollector.collectPluginJars(
                defaultPluginDir, List.of(cJar), List.of(extraDir));

        assertThat(result).containsExactlyInAnyOrder(
                aJar.toAbsolutePath().normalize(),
                bJar.toAbsolutePath().normalize(),
                cJar.toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("a missing default plugin directory is a no-op, not an error")
    void missingDefaultDirectoryIsNoOp() {
        final Path missing = tempDir.resolve("does-not-exist");

        final Set<Path> result = PluginJarCollector.collectPluginJars(missing, List.of(), List.of());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("a null default plugin directory is a no-op, not an error")
    void nullDefaultDirectoryIsNoOp() {
        final Set<Path> result = PluginJarCollector.collectPluginJars(null, List.of(), List.of());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("a regular file passed as the default plugin directory is a no-op, not an error")
    void nonDirectoryDefaultIsNoOp() throws IOException {
        final Path notADirectory = createFile(tempDir.resolve("i-am-a-file.jar"));

        final Set<Path> result = PluginJarCollector.collectPluginJars(notADirectory, List.of(), List.of());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("a missing explicit extra directory is a no-op, not an error")
    void missingExplicitExtraDirectoryIsNoOp() {
        final Path missingDir = tempDir.resolve("also-does-not-exist");

        final Set<Path> result = PluginJarCollector.collectPluginJars(
                defaultPluginDir, List.of(), List.of(missingDir));

        assertThat(result).containsExactlyInAnyOrder(
                aJar.toAbsolutePath().normalize(),
                bJar.toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("a missing explicit extra file is a no-op, not an error")
    void missingExplicitExtraFileIsNoOp() {
        final Path missingFile = tempDir.resolve("also-does-not-exist.jar");

        final Set<Path> result = PluginJarCollector.collectPluginJars(
                defaultPluginDir, List.of(missingFile), List.of());

        assertThat(result).containsExactlyInAnyOrder(
                aJar.toAbsolutePath().normalize(),
                bJar.toAbsolutePath().normalize());
    }

    private static Path createFile(final Path path) throws IOException {
        Files.createFile(path);
        return path;
    }
}
