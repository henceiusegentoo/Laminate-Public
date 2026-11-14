package eu.lschreiber.laminate.runtime;

import java.util.List;

/**
 * Describes a hybrid plugin and its mixin config resources.
 *
 * @param name plugin name
 * @param mixinConfigs mixin configuration resources inside the jar
 */
record HybridPluginDescriptor(String name, List<String> mixinConfigs) {
}
