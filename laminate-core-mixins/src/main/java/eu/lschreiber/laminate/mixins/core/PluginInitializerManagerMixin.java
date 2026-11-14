package eu.lschreiber.laminate.mixins.core;

import com.mojang.logging.LogUtils;
import eu.lschreiber.laminate.bridge.LaminateBridge;
import io.papermc.paper.plugin.PluginInitializerManager;
import io.papermc.paper.plugin.entrypoint.LaunchEntryPointHandler;
import io.papermc.paper.plugin.provider.PluginProvider;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import joptsimple.OptionSet;
import net.minecraft.server.dedicated.DedicatedServer;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps hybrid plugins out of vanilla plugin listings and warns on reload. */
@Mixin(PluginInitializerManager.class)
public abstract class PluginInitializerManagerMixin {

    /** Uses Paper's logger, which is already available here. */
    private static final Logger LOGGER = LogUtils.getClassLogger();

    @Inject(method = "load", at = @At("TAIL"))
    private static void laminate$afterLoad(final OptionSet optionSet, final CallbackInfo ci) {
        cullFailedHybridPlugins();
        logLoadedHybridPlugins();
    }

    /** Removes failed hybrid plugins from provider storage. */
    private static void cullFailedHybridPlugins() {
        final Set<String> failedHybridPlugins = LaminateBridge.failedHybridPluginNames();
        if (failedHybridPlugins.isEmpty()) {
            return;
        }
        LaunchEntryPointHandler.INSTANCE.getStorage().forEach((entrypoint, providerStorage) -> {
            final Iterable<?> registeredProviders = providerStorage.getRegisteredProviders();
            if (!(registeredProviders instanceof Collection<?> collection)) {
                LOGGER.warn("[Laminate] Unable to filter failed hybrid plugins for "
                        + "entrypoint " + entrypoint + " because provider storage is not mutable.");
                return;
            }
            @SuppressWarnings("unchecked")
            final Collection<PluginProvider<?>> typedProviders = (Collection<PluginProvider<?>>) collection;
            typedProviders.removeIf(provider ->
                    failedHybridPlugins.contains(provider.getMeta().getName().toLowerCase(Locale.ROOT)));
        });
    }

    /** Logs loaded hybrid plugins. */
    private static void logLoadedHybridPlugins() {
        final Set<String> loadedHybridPlugins = LaminateBridge.loadedHybridPluginNames();
        if (loadedHybridPlugins.isEmpty()) {
            return;
        }
        final Set<String> laminatePluginNames = new TreeSet<>();
        LaunchEntryPointHandler.INSTANCE.getStorage().forEach((entrypoint, providerStorage) ->
                providerStorage.getRegisteredProviders().forEach(provider -> {
                    if (loadedHybridPlugins.contains(provider.getMeta().getName())) {
                        laminatePluginNames.add(
                                String.format("%s (%s)", provider.getMeta().getName(), provider.getMeta().getVersion()));
                    }
                }));
        if (laminatePluginNames.isEmpty()) {
            return;
        }
        final boolean multiline = LOGGER.isDebugEnabled();
        final String separator = multiline ? "\n - " : ", ";
        final String prefix = multiline ? ":\n - " : ": ";
        LOGGER.info("[Laminate] Laminate plugins (" + laminatePluginNames.size() + ")" + prefix
                + String.join(separator, laminatePluginNames));
    }

    @Inject(method = "reload", at = @At("TAIL"))
    private static void laminate$afterReload(final DedicatedServer dedicatedServer, final CallbackInfo ci) {
        if (!LaminateBridge.loadedHybridPluginNames().isEmpty()) {
            LOGGER.warn("[Laminate] ======== WARNING ========");
            LOGGER.warn("[Laminate] You are reloading while having Laminate hybrid plugins installed on your server.");
            LOGGER.warn("[Laminate] Hybrid plugins do NOT support being reloaded.");
            LOGGER.warn("[Laminate] Mixin bytecode transformations are permanent for the lifetime of this server process.");
            LOGGER.warn("[Laminate] =========================");
        }
    }
}
