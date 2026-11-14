package eu.lschreiber.testplugin;

import com.mojang.brigadier.tree.LiteralCommandNode;
import eu.lschreiber.testplugin.accessor.MinecraftServerAccessor;
import eu.lschreiber.testplugin.listener.TickListener;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.minecraft.server.MinecraftServer;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.plugin.java.JavaPlugin;

/** Demo plugin for Laminate's accessor and event features. */
public final class TestPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(new TickListener(), this);
        registerCommands();
    }

    private void registerCommands() {
        final LiteralCommandNode<CommandSourceStack> testCommand = Commands.literal("testcmd")
                .executes(this::executeTestCommand)
                .build();

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, commands -> commands.registrar().register(testCommand));
    }

    private int executeTestCommand(final com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        final MinecraftServer server = ((CraftServer) getServer()).getServer();
        final MinecraftServerAccessor accessor = MinecraftServerAccessor.of(server);
        accessor.write("Hello World");
        return 1;
    }
}
