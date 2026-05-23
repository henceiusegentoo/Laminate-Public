package io.papermc.paper.mixin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Reads and validates <em>hybrid plugin descriptors</em> from Bukkit plugin
 * JARs.
 *
 * <p>A hybrid plugin is a standard Bukkit/Paper plugin whose {@code plugin.yml}
 * contains the additional key {@code hybrid: true} together with a
 * {@code mixins} list pointing at Mixin JSON configuration files inside the
 * JAR. This reader extracts that metadata and validates resource presence.</p>
 *
 * @see HybridPluginMixinBootstrap
 */
final class HybridDescriptorReader {

    private HybridDescriptorReader() {
    }

    /**
     * Attempts to read a {@link HybridDescriptor} from the given plugin JAR.
     *
     * <p>Reads {@code plugin.yml} inside the JAR, checks for
     * {@code hybrid: true}, and extracts the plugin name and mixin configs.
     * Each referenced mixin config must exist as an entry in the JAR.</p>
     *
     * @param pluginJar the plugin JAR to inspect
     * @return the parsed descriptor, or {@code null} if the JAR is not a
     *         hybrid plugin
     * @throws IOException                   if the JAR cannot be read
     * @throws InvalidConfigurationException if the descriptor is malformed
     */
    static HybridDescriptor read(final Path pluginJar)
            throws IOException, InvalidConfigurationException {

        try (final JarFile jarFile = new JarFile(pluginJar.toFile())) {
            final JarEntry pluginYmlEntry = jarFile.getJarEntry("plugin.yml");
            if (pluginYmlEntry == null) {
                return null;
            }

            final YamlConfiguration yaml = parseYaml(jarFile, pluginYmlEntry);

            if (!yaml.getBoolean("hybrid", false)) {
                return null;
            }

            final String name = extractPluginName(yaml);
            final List<String> mixinConfigs = extractMixinConfigs(yaml);
            validateMixinConfigsExist(jarFile, mixinConfigs);

            return new HybridDescriptor(name, Collections.unmodifiableList(mixinConfigs));
        }
    }

    private static YamlConfiguration parseYaml(final JarFile jarFile,
                                               final JarEntry entry)
            throws IOException, InvalidConfigurationException {

        final String content;
        try (final var inputStream = jarFile.getInputStream(entry)) {
            content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
        final YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(content);
        return yaml;
    }

    private static String extractPluginName(final YamlConfiguration yaml)
            throws InvalidConfigurationException {
        final String name = yaml.getString("name");
        if (name == null || name.isBlank()) {
            throw new InvalidConfigurationException(
                    "hybrid plugin has no name in plugin.yml");
        }
        return name;
    }

    private static List<String> extractMixinConfigs(final YamlConfiguration yaml)
            throws InvalidConfigurationException {
        final List<String> mixins = yaml.getStringList("mixins");
        if (mixins.isEmpty()) {
            throw new InvalidConfigurationException(
                    "hybrid plugin declares hybrid=true but no mixins were provided");
        }
        return mixins;
    }

    private static void validateMixinConfigsExist(final JarFile jarFile,
                                                  final List<String> mixinConfigs)
            throws InvalidConfigurationException {
        for (final String config : mixinConfigs) {
            if (jarFile.getJarEntry(config) == null) {
                throw new InvalidConfigurationException(
                        "missing mixin configuration resource: " + config);
            }
        }
    }
}
