package io.papermc.paper.plugin.provider.type.spigot;

import io.papermc.paper.plugin.provider.PluginProvider;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import org.bukkit.plugin.PluginDescriptionFile;

/**
 * A {@link PluginProvider} for <em>Laminate hybrid plugins</em> — standard
 * Bukkit/Paper plugins ({@code plugin.yml}) that additionally apply
 * {@link org.spongepowered.asm.mixin.Mixin} bytecode transformations to the
 * server at runtime.
 *
 * <p>This class is a marker subtype of {@link SpigotPluginProvider}. All
 * plugin-loading logic is inherited unchanged; the distinct type is used by
 * {@link io.papermc.paper.command.PaperPluginsCommand} to display hybrid
 * plugins in a separate "Laminate Plugins" category, and by the runtime
 * plugin-provider storages to prevent hot-reloading (Mixin transformations
 * are irreversible).</p>
 *
 * <p>Instances are created by {@link SpigotPluginProviderFactory} when the
 * plugin name appears in
 * {@link io.papermc.paper.mixin.LaminateMixinBootstrap#getLoadedHybridPluginNames()}.</p>
 *
 * @see SpigotPluginProvider
 * @see io.papermc.paper.mixin.LaminateMixinBootstrap
 */
public final class LaminatePluginProvider extends SpigotPluginProvider {

    LaminatePluginProvider(
        final Path path, final JarFile file,
        final PluginDescriptionFile description,
        final List<Path> paperLibraryPaths
    ) {
        super(path, file, description, paperLibraryPaths);
    }

    @Override
    public String toString() {
        return "LaminatePluginProvider{"
            + "path=" + getSource()
            + ", description=" + getMeta()
            + '}';
    }
}


