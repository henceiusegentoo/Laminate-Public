package eu.lschreiber.laminate.mixins.core;

import eu.lschreiber.laminate.bridge.LaminateBridge;
import io.papermc.paper.command.subcommands.ReloadCommand;
import java.util.Set;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.format.NamedTextColor.RED;

/** Blocks {@code /reload} while hybrid plugins are active. */
@Mixin(ReloadCommand.class)
public abstract class ReloadCommandMixin {

    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private void laminate$blockReloadWithHybridPlugins(
            final CommandSender sender,
            final String subCommand,
            final String[] args,
            final CallbackInfoReturnable<Boolean> callbackInfoReturnable) {
        final Set<String> loadedHybridPlugins = LaminateBridge.loadedHybridPluginNames();
        if (loadedHybridPlugins.isEmpty()) {
            return;
        }
        Command.broadcastCommandMessage(sender,
                text("Reloading the server while hybrid plugins are loaded is not supported", RED));
        Command.broadcastCommandMessage(sender,
                text("Consider restarting the server instead. Reload cancelled", RED));
        callbackInfoReturnable.setReturnValue(false);
        callbackInfoReturnable.cancel();
    }
}
