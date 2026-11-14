package eu.lschreiber.laminate.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.yaml.snakeyaml.Yaml;

/**
 * Reads and validates <em>hybrid plugin descriptors</em> from Bukkit plugin JARs.
 *
 * <p>A hybrid plugin is a standard Bukkit/Paper plugin whose {@code plugin.yml} contains the
 * additional key {@code hybrid: true} together with a {@code mixins} list pointing at Mixin JSON
 * configuration files inside the JAR. This reader extracts that metadata and validates resource
 * presence.</p>
 *
 * <p>Port of the reference fork's {@code io.papermc.paper.mixin.HybridDescriptorReader}, using
 * {@code org.yaml:snakeyaml} directly instead of Bukkit's {@code YamlConfiguration} (which lives
 * in {@code paper-api}/CraftBukkit, not a dependency of this module — see the phase 3 plan's
 * Global Constraints).</p>
 */
final class HybridPluginDescriptorReader {

    private static final String PLUGIN_YML_ENTRY = "plugin.yml";

    private HybridPluginDescriptorReader() {
    }

    /**
     * Attempts to read a {@link HybridPluginDescriptor} from the given plugin JAR.
     *
     * <p>Reads {@code plugin.yml} inside the JAR, checks for {@code hybrid: true}, and extracts
     * the plugin name and mixin configs. Each referenced mixin config must exist as an entry in
     * the JAR.</p>
     *
     * @param pluginJar the plugin JAR to inspect
     * @return the parsed descriptor, or {@code null} if the JAR is not a hybrid plugin (no
     *         {@code plugin.yml}, an empty/non-mapping {@code plugin.yml}, or {@code hybrid}
     *         absent/false)
     * @throws IOException                       if the JAR or its {@code plugin.yml} entry cannot be read
     * @throws HybridPluginDescriptorException    if the descriptor declares {@code hybrid: true} but
     *                                             is otherwise malformed
     */
    static HybridPluginDescriptor read(final Path pluginJar) throws IOException {
        try (JarFile jarFile = new JarFile(pluginJar.toFile())) {
            final JarEntry pluginYmlEntry = jarFile.getJarEntry(PLUGIN_YML_ENTRY);
            if (pluginYmlEntry == null) {
                return null;
            }

            final Map<String, Object> yaml = parseYaml(jarFile, pluginYmlEntry);
            if (yaml == null || !isHybrid(yaml)) {
                return null;
            }

            final String name = extractPluginName(yaml);
            final List<String> mixinConfigs = extractMixinConfigs(yaml);
            validateMixinConfigsExist(jarFile, mixinConfigs);

            return new HybridPluginDescriptor(name, List.copyOf(mixinConfigs));
        }
    }

    /**
     * Parses {@code plugin.yml}'s content into a raw key/value map via snakeyaml.
     *
     * @return the parsed mapping, or {@code null} if the document is empty (snakeyaml's
     *         {@code load} returns {@code null} for an empty/whitespace-only/all-comments document)
     * @throws HybridPluginDescriptorException if the document parses to something other than a
     *                                          YAML mapping (e.g. a bare list or scalar at the root)
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseYaml(final JarFile jarFile, final JarEntry entry) throws IOException {
        try (InputStream inputStream = jarFile.getInputStream(entry)) {
            final Object loaded = new Yaml().load(inputStream);
            if (loaded == null) {
                return null;
            }
            if (!(loaded instanceof Map<?, ?>)) {
                throw new HybridPluginDescriptorException(
                        PLUGIN_YML_ENTRY + " does not contain a YAML mapping at its root");
            }
            return (Map<String, Object>) loaded;
        }
    }

    private static boolean isHybrid(final Map<String, Object> yaml) {
        return yaml.get("hybrid") instanceof Boolean bool && bool;
    }

    private static String extractPluginName(final Map<String, Object> yaml) {
        final Object rawName = yaml.get("name");
        final String name = rawName == null ? null : String.valueOf(rawName);
        if (name == null || name.isBlank()) {
            throw new HybridPluginDescriptorException("hybrid plugin has no name in plugin.yml");
        }
        return name;
    }

    private static List<String> extractMixinConfigs(final Map<String, Object> yaml) {
        final Object rawMixins = yaml.get("mixins");
        final List<String> mixins = new ArrayList<>();
        if (rawMixins instanceof List<?> list) {
            for (final Object element : list) {
                if (element != null) {
                    mixins.add(String.valueOf(element));
                }
            }
        }
        if (mixins.isEmpty()) {
            throw new HybridPluginDescriptorException(
                    "hybrid plugin declares hybrid=true but no mixins were provided");
        }
        return mixins;
    }

    private static void validateMixinConfigsExist(final JarFile jarFile, final List<String> mixinConfigs) {
        for (final String config : mixinConfigs) {
            if (jarFile.getJarEntry(config) == null) {
                throw new HybridPluginDescriptorException("missing mixin configuration resource: " + config);
            }
        }
    }
}
