package eu.lschreiber.laminate.mixins.core;

import com.mojang.brigadier.context.CommandContext;
import eu.lschreiber.laminate.bridge.LaminateBridge;
import io.papermc.paper.command.PaperPluginsCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.provider.PluginProvider;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.command.CommandSender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Adds a Laminate section to {@code /plugins} and hides hybrid plugins from vanilla categories. */
@Mixin(PaperPluginsCommand.class)
public abstract class PaperPluginsCommandMixin {

    private static final TextColor LAMINATE_COLOR = TextColor.color(0xff4245);

    @Redirect(
            method = "execute",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/TreeMap;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object laminate$skipHybridPluginInVanillaCategories(
            final TreeMap<Object, Object> map, final Object key, final Object value) {
        if (value instanceof final PluginProvider<?> provider) {
            final String name = provider.getMeta().getName();
            for (final String hybridName : LaminateBridge.loadedHybridPluginNames()) {
                if (hybridName.equalsIgnoreCase(name)) {
                    return null;
                }
            }
        }
        return map.put(key, value);
    }

    @Inject(method = "execute", at = @At("TAIL"))
    private void laminate$appendLaminatePluginsLine(
            final CommandContext<CommandSourceStack> context, final CallbackInfoReturnable<Integer> cir) {
        final Set<String> loadedHybridPlugins = LaminateBridge.loadedHybridPluginNames();
        if (loadedHybridPlugins.isEmpty()) {
            return;
        }
        final Set<String> sortedNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        sortedNames.addAll(loadedHybridPlugins);

        final CommandSender sender = context.getSource().getSender();
        sender.sendMessage(Component.text("Laminate Plugins (" + sortedNames.size() + "):", LAMINATE_COLOR));
        sender.sendMessage(Component.text()
                .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                .append(Component.text(String.join(", ", sortedNames), LAMINATE_COLOR))
                .build());
    }
}
