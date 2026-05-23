package io.papermc.paper.plugin;

import com.mojang.logging.LogUtils;
import io.papermc.paper.configuration.PaperConfigurations;
import io.papermc.paper.mixin.LaminateMixinBootstrap;
import io.papermc.paper.plugin.entrypoint.Entrypoint;
import io.papermc.paper.plugin.entrypoint.LaunchEntryPointHandler;
import io.papermc.paper.plugin.provider.PluginProvider;
import io.papermc.paper.plugin.provider.type.paper.PaperPluginParent;
import io.papermc.paper.plugin.provider.type.spigot.LaminatePluginProvider;
import io.papermc.paper.plugin.provider.type.spigot.SpigotPluginProvider;
import io.papermc.paper.pluginremap.PluginRemapper;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import joptsimple.OptionSet;
import net.minecraft.server.dedicated.DedicatedServer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.LibraryLoader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

public class PluginInitializerManager {

    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static PluginInitializerManager impl;
    public final io.papermc.paper.pluginremap.@org.checkerframework.checker.nullness.qual.MonotonicNonNull PluginRemapper pluginRemapper; // Paper
    private final Path pluginDirectory;
    private final Path updateDirectory;

    PluginInitializerManager(final Path pluginDirectory, final Path updateDirectory) {
        this.pluginDirectory = pluginDirectory;
        this.updateDirectory = updateDirectory;
        this.pluginRemapper = Boolean.getBoolean("paper.disablePluginRemapping")
            ? null
            : PluginRemapper.create(pluginDirectory);
        LibraryLoader.REMAPPER = this.pluginRemapper == null ? Function.identity() : this.pluginRemapper::remapLibraries;
    }

    private static PluginInitializerManager parse(@NotNull final OptionSet minecraftOptionSet) throws Exception {
        // We have to load the bukkit configuration inorder to get the update folder location.
        final File configFileLocationBukkit = (File) minecraftOptionSet.valueOf("bukkit-settings");

        final Path pluginDirectory = ((File) minecraftOptionSet.valueOf("plugins")).toPath();

        final YamlConfiguration configuration = PaperConfigurations.loadLegacyConfigFile(configFileLocationBukkit);

        final String updateDirectoryName = configuration.getString("settings.update-folder", "update");
        if (updateDirectoryName.isBlank()) {
            return new PluginInitializerManager(pluginDirectory, null);
        }

        final Path resolvedUpdateDirectory = pluginDirectory.resolve(updateDirectoryName);
        if (!Files.isDirectory(resolvedUpdateDirectory)) {
            if (Files.exists(resolvedUpdateDirectory)) {
                LOGGER.error("Misconfigured update directory!");
                LOGGER.error("Your configured update directory ({}) in bukkit.yml is pointing to a non-directory path. " +
                    "Auto updating functionality will not work.", resolvedUpdateDirectory);
            }
            return new PluginInitializerManager(pluginDirectory, null);
        }

        boolean isSameFile;
        try {
            isSameFile = Files.isSameFile(resolvedUpdateDirectory, pluginDirectory);
        } catch (final IOException e) {
            LOGGER.error("Misconfigured update directory!");
            LOGGER.error("Failed to compare update/plugin directory", e);
            return new PluginInitializerManager(pluginDirectory, null);
        }

        if (isSameFile) {
            LOGGER.error("Misconfigured update directory!");
            LOGGER.error(("Your configured update directory (%s) in bukkit.yml is pointing to the same location as the plugin directory (%s). " +
                "Disabling auto updating functionality.").formatted(resolvedUpdateDirectory, pluginDirectory));

            return new PluginInitializerManager(pluginDirectory, null);
        }

        return new PluginInitializerManager(pluginDirectory, resolvedUpdateDirectory);
    }

    public static PluginInitializerManager init(final OptionSet optionSet) throws Exception {
        impl = parse(optionSet);
        return impl;
    }

    public static PluginInitializerManager instance() {
        return impl;
    }

    public static void load(OptionSet optionSet) throws Exception {
        LOGGER.info("Initializing plugins...");
        // We have to load the bukkit configuration inorder to get the update folder location.
        io.papermc.paper.plugin.PluginInitializerManager pluginSystem = io.papermc.paper.plugin.PluginInitializerManager.init(optionSet);
        if (pluginSystem.pluginRemapper != null) pluginSystem.pluginRemapper.loadingPlugins();

        @SuppressWarnings("unchecked") final java.util.List<Path> files = ((java.util.List<File>) optionSet.valuesOf("add-plugin")).stream().map(File::toPath).toList();
        @SuppressWarnings("unchecked") final java.util.List<Path> dirs = ((java.util.List<File>) optionSet.valuesOf("add-plugin-dir")).stream().map(File::toPath).toList();

        // Hybrid mixins must be registered before Paper begins normal plugin registration/loading.
        final Set<String> failedHybridPlugins = LaminateMixinBootstrap.bootstrap(pluginSystem.pluginDirectoryPath(), files, dirs);

        // Register the default plugin directory
        io.papermc.paper.plugin.util.EntrypointUtil.registerProvidersFromSource(io.papermc.paper.plugin.provider.source.DirectoryProviderSource.INSTANCE, pluginSystem.pluginDirectoryPath());

        // Register plugins from the flag
        io.papermc.paper.plugin.util.EntrypointUtil.registerProvidersFromSource(io.papermc.paper.plugin.provider.source.PluginFlagProviderSource.INSTANCE, files);

        dirs.forEach(pluginDir -> io.papermc.paper.plugin.util.EntrypointUtil.registerProvidersFromSource(io.papermc.paper.plugin.provider.source.DirectoryProviderSource.INSTANCE_NO_CREATE, pluginDir));

        if (!failedHybridPlugins.isEmpty()) {
            LaunchEntryPointHandler.INSTANCE.getStorage().forEach((entrypoint, providerStorage) -> {
                final Iterable<?> registeredProviders = providerStorage.getRegisteredProviders();
                if (!(registeredProviders instanceof Collection<?> collection)) {
                    LOGGER.warn("Unable to filter failed hybrid plugins for entrypoint {} because provider storage is not mutable.", entrypoint);
                    return;
                }
                @SuppressWarnings("unchecked") final Collection<PluginProvider<?>> typedProviders = (Collection<PluginProvider<?>>) collection;
                typedProviders.removeIf(provider -> failedHybridPlugins.contains(provider.getMeta().getName().toLowerCase(java.util.Locale.ENGLISH)));
            });
        }

        final Set<String> paperPluginNames = new TreeSet<>();
        final Set<String> legacyPluginNames = new TreeSet<>();
        final Set<String> laminatePluginNames = new TreeSet<>();
        LaunchEntryPointHandler.INSTANCE.getStorage().forEach((entrypoint, providerStorage) -> providerStorage.getRegisteredProviders().forEach(provider -> {
            // LaminatePluginProvider must be checked before SpigotPluginProvider because it is a subtype.
            if (provider instanceof final LaminatePluginProvider laminate) {
                laminatePluginNames.add(String.format("%s (%s)", laminate.getMeta().getName(), laminate.getMeta().getVersion()));
            } else if (provider instanceof final SpigotPluginProvider legacy) {
                legacyPluginNames.add(String.format("%s (%s)", legacy.getMeta().getName(), legacy.getMeta().getVersion()));
            } else if (provider instanceof PaperPluginParent.PaperServerPluginProvider) {
                paperPluginNames.add(String.format("%s (%s)", provider.getMeta().getName(), provider.getMeta().getVersion()));
            }
        }));
        final int total = laminatePluginNames.size() + paperPluginNames.size() + legacyPluginNames.size();
        LOGGER.info("Initialized {} plugin{}", total, total == 1 ? "" : "s");
        if (!laminatePluginNames.isEmpty()) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.info("Laminate plugins ({}):\n - {}", laminatePluginNames.size(), String.join("\n - ", laminatePluginNames));
            } else {
                LOGGER.info("Laminate plugins ({}):\n - {}", laminatePluginNames.size(), String.join(", ", laminatePluginNames));
            }
        }
        if (!paperPluginNames.isEmpty()) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.info("Paper plugins ({}):\n - {}", paperPluginNames.size(), String.join("\n - ", paperPluginNames));
            } else {
                LOGGER.info("Paper plugins ({}):\n - {}", paperPluginNames.size(), String.join(", ", paperPluginNames));
            }
        }
        if (!legacyPluginNames.isEmpty()) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.info("Bukkit plugins ({}):\n - {}", legacyPluginNames.size(), String.join("\n - ", legacyPluginNames));
            } else {
                LOGGER.info("Bukkit plugins ({}):\n - {}", legacyPluginNames.size(), String.join(", ", legacyPluginNames));
            }
        }
    }

    // This will be the end of me...
    public static void reload(DedicatedServer dedicatedServer) {
        // Wipe the provider storage
        LaunchEntryPointHandler.INSTANCE.populateProviderStorage();
        try {
            load(dedicatedServer.options);
        } catch (Exception e) {
            throw new RuntimeException("Failed to reload!", e);
        }

        boolean hasPaperPlugin = false;
        boolean hasLaminatePlugin = false;
        for (PluginProvider<?> provider : LaunchEntryPointHandler.INSTANCE.getStorage().get(Entrypoint.PLUGIN).getRegisteredProviders()) {
            if (provider instanceof PaperPluginParent.PaperServerPluginProvider) {
                hasPaperPlugin = true;
            } else if (provider instanceof LaminatePluginProvider) {
                hasLaminatePlugin = true;
            }
            if (hasPaperPlugin && hasLaminatePlugin) break;
        }

        if (hasPaperPlugin) {
            LOGGER.warn("======== WARNING ========");
            LOGGER.warn("You are reloading while having Paper plugins installed on your server.");
            LOGGER.warn("Paper plugins do NOT support being reloaded. This will cause some unexpected issues.");
            LOGGER.warn("=========================");
        }
        if (hasLaminatePlugin) {
            LOGGER.warn("======== WARNING ========");
            LOGGER.warn("You are reloading while having Laminate hybrid plugins installed on your server.");
            LOGGER.warn("Laminate hybrid plugins do NOT support being reloaded.");
            LOGGER.warn("Mixin bytecode transformations are permanent for the lifetime of this server process.");
            LOGGER.warn("=========================");
        }
    }

    @NotNull
    public Path pluginDirectoryPath() {
        return pluginDirectory;
    }

    @Nullable
    public Path pluginUpdatePath() {
        return updateDirectory;
    }
}
