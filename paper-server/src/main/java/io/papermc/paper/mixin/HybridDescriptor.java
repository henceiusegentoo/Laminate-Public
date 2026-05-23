package io.papermc.paper.mixin;

import java.util.List;

/**
 * Immutable descriptor for a single hybrid plugin, holding the plugin name
 * (from {@code plugin.yml}) and the list of Mixin JSON configuration resource
 * paths declared under the {@code mixins} key.
 *
 * @param name         the plugin name as declared in {@code plugin.yml}
 * @param mixinConfigs an unmodifiable list of mixin configuration resource
 *                     paths (e.g. {@code "mixins.myplugin.json"})
 */
record HybridDescriptor(String name, List<String> mixinConfigs) {
}
