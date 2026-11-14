package eu.lschreiber.laminate.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fixture-JAR-based coverage of {@link HybridPluginDescriptorReader#read(Path)}, matching the
 * five scenarios called out in the phase 3 plan's Task 2 verify step, plus a sixth
 * (missing/blank {@code name}) surfaced separately in the self-review checklist.
 *
 * <p>Every fixture JAR is built for real (via {@link JarOutputStream}) in a per-test temp
 * directory rather than mocked, so this exercises the actual {@link java.util.jar.JarFile}
 * reading and snakeyaml parsing path.</p>
 */
@DisplayName("HybridPluginDescriptorReader")
class HybridPluginDescriptorReaderTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("a valid hybrid plugin.yml with a mixins list whose entries exist in the jar yields a descriptor")
    void validHybridDescriptor() throws IOException {
        final Path jar = buildJar("valid.jar", Map.of(
                "plugin.yml", """
                        name: TestPlugin
                        version: 1.0
                        main: eu.lschreiber.testplugin.TestPlugin
                        hybrid: true
                        mixins:
                          - mixins.testplugin.json
                        """,
                "mixins.testplugin.json", "{}"
        ));

        final HybridPluginDescriptor descriptor = HybridPluginDescriptorReader.read(jar);

        assertThat(descriptor).isNotNull();
        assertThat(descriptor.name()).isEqualTo("TestPlugin");
        assertThat(descriptor.mixinConfigs()).containsExactly("mixins.testplugin.json");
    }

    @Test
    @DisplayName("hybrid absent from plugin.yml returns null")
    void hybridAbsentReturnsNull() throws IOException {
        final Path jar = buildJar("not-hybrid-absent.jar", Map.of(
                "plugin.yml", """
                        name: OrdinaryPlugin
                        version: 1.0
                        main: eu.lschreiber.plugin.OrdinaryPlugin
                        """
        ));

        assertThat(HybridPluginDescriptorReader.read(jar)).isNull();
    }

    @Test
    @DisplayName("hybrid: false in plugin.yml returns null")
    void hybridFalseReturnsNull() throws IOException {
        final Path jar = buildJar("not-hybrid-false.jar", Map.of(
                "plugin.yml", """
                        name: OrdinaryPlugin
                        version: 1.0
                        main: eu.lschreiber.plugin.OrdinaryPlugin
                        hybrid: false
                        """
        ));

        assertThat(HybridPluginDescriptorReader.read(jar)).isNull();
    }

    @Test
    @DisplayName("hybrid: true with no mixins key throws")
    void hybridTrueNoMixinsThrows() throws IOException {
        final Path jar = buildJar("no-mixins.jar", Map.of(
                "plugin.yml", """
                        name: BrokenHybridPlugin
                        version: 1.0
                        main: eu.lschreiber.plugin.BrokenHybridPlugin
                        hybrid: true
                        """
        ));

        assertThatThrownBy(() -> HybridPluginDescriptorReader.read(jar))
                .isInstanceOf(HybridPluginDescriptorException.class)
                .hasMessageContaining("mixins");
    }

    @Test
    @DisplayName("hybrid: true with an empty mixins list throws")
    void hybridTrueEmptyMixinsThrows() throws IOException {
        final Path jar = buildJar("empty-mixins.jar", Map.of(
                "plugin.yml", """
                        name: BrokenHybridPlugin
                        version: 1.0
                        main: eu.lschreiber.plugin.BrokenHybridPlugin
                        hybrid: true
                        mixins: []
                        """
        ));

        assertThatThrownBy(() -> HybridPluginDescriptorReader.read(jar))
                .isInstanceOf(HybridPluginDescriptorException.class)
                .hasMessageContaining("mixins");
    }

    @Test
    @DisplayName("hybrid: true naming a mixins config that doesn't exist in the jar throws, naming it")
    void hybridTrueMissingMixinConfigThrows() throws IOException {
        final Path jar = buildJar("missing-mixin-config.jar", Map.of(
                "plugin.yml", """
                        name: BrokenHybridPlugin
                        version: 1.0
                        main: eu.lschreiber.plugin.BrokenHybridPlugin
                        hybrid: true
                        mixins:
                          - mixins.doesnotexist.json
                        """
        ));

        assertThatThrownBy(() -> HybridPluginDescriptorReader.read(jar))
                .isInstanceOf(HybridPluginDescriptorException.class)
                .hasMessageContaining("mixins.doesnotexist.json");
    }

    @Test
    @DisplayName("a jar with no plugin.yml at all returns null")
    void noPluginYmlReturnsNull() throws IOException {
        final Path jar = buildJar("no-descriptor.jar", Map.of(
                "some-other-resource.txt", "not a plugin descriptor"
        ));

        assertThat(HybridPluginDescriptorReader.read(jar)).isNull();
    }

    @Test
    @DisplayName("hybrid: true with a blank name throws")
    void hybridTrueBlankNameThrows() throws IOException {
        final Path jar = buildJar("blank-name.jar", Map.of(
                "plugin.yml", """
                        name: "   "
                        version: 1.0
                        main: eu.lschreiber.plugin.BrokenHybridPlugin
                        hybrid: true
                        mixins:
                          - mixins.testplugin.json
                        """,
                "mixins.testplugin.json", "{}"
        ));

        assertThatThrownBy(() -> HybridPluginDescriptorReader.read(jar))
                .isInstanceOf(HybridPluginDescriptorException.class)
                .hasMessageContaining("name");
    }

    @Test
    @DisplayName("hybrid: true with no name key at all throws")
    void hybridTrueMissingNameThrows() throws IOException {
        final Path jar = buildJar("missing-name.jar", Map.of(
                "plugin.yml", """
                        version: 1.0
                        main: eu.lschreiber.plugin.BrokenHybridPlugin
                        hybrid: true
                        mixins:
                          - mixins.testplugin.json
                        """,
                "mixins.testplugin.json", "{}"
        ));

        assertThatThrownBy(() -> HybridPluginDescriptorReader.read(jar))
                .isInstanceOf(HybridPluginDescriptorException.class)
                .hasMessageContaining("name");
    }

    private Path buildJar(final String jarName, final Map<String, String> entries) throws IOException {
        final Path jarPath = tempDir.resolve(jarName);
        try (OutputStream fileOut = java.nio.file.Files.newOutputStream(jarPath);
             JarOutputStream jarOut = new JarOutputStream(fileOut)) {
            for (final Map.Entry<String, String> entry : new LinkedHashMap<>(entries).entrySet()) {
                jarOut.putNextEntry(new JarEntry(entry.getKey()));
                jarOut.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                jarOut.closeEntry();
            }
        }
        return jarPath;
    }
}
