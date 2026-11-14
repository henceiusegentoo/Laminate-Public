package eu.lschreiber.laminate.launcher;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parsed hybrid-plugin CLI options passed through by {@code LaminateMain}.
 *
 * <p>Only Laminate-specific flags are consumed; everything else is left for Paper/paperclip.</p>
 *
 * @param pluginDir plugin directory to scan
 * @param addPluginFiles extra plugin JARs to scan
 * @param addPluginDirs extra plugin directories to scan
 */
public record PluginArguments(Path pluginDir, List<Path> addPluginFiles, List<Path> addPluginDirs) {

    static final String PLUGINS_FLAG = "--plugins";
    static final String ADD_PLUGIN_FLAG = "--add-plugin";
    static final String ADD_PLUGIN_DIR_FLAG = "--add-plugin-dir";
    static final String DEFAULT_PLUGIN_DIR = "plugins";

    /**
     * Parses the Laminate-specific flags from {@code args}.
     *
     * @param args raw process arguments
     * @return parsed plugin arguments
     * @throws IllegalArgumentException if a flag is missing its value
     */
    public static PluginArguments parse(String[] args) {
        Path pluginDir = Path.of(DEFAULT_PLUGIN_DIR);
        List<Path> addPluginFiles = new ArrayList<>();
        List<Path> addPluginDirs = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (PLUGINS_FLAG.equals(arg)) {
                pluginDir = Path.of(requireValue(args, i, PLUGINS_FLAG));
                i++;
            } else if (ADD_PLUGIN_FLAG.equals(arg)) {
                addPluginFiles.add(Path.of(requireValue(args, i, ADD_PLUGIN_FLAG)));
                i++;
            } else if (ADD_PLUGIN_DIR_FLAG.equals(arg)) {
                addPluginDirs.add(Path.of(requireValue(args, i, ADD_PLUGIN_DIR_FLAG)));
                i++;
            }
        }

        return new PluginArguments(
                pluginDir,
                Collections.unmodifiableList(addPluginFiles),
                Collections.unmodifiableList(addPluginDirs));
    }

    private static String requireValue(String[] args, int flagIndex, String flag) {
        int valueIndex = flagIndex + 1;
        if (valueIndex >= args.length) {
            throw new IllegalArgumentException(flag + " requires a value");
        }
        return args[valueIndex];
    }
}
