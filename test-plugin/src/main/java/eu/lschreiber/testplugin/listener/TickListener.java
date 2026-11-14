package eu.lschreiber.testplugin.listener;

import eu.lschreiber.testplugin.events.ServerLevelTickStartEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.logging.Logger;

/** Consumes the bridged tick event. */
public final class TickListener implements Listener {

    private static final Logger LOGGER = Logger.getLogger(TickListener.class.getName());

    @EventHandler
    public void onServerLevelTickStart(final ServerLevelTickStartEvent event) {
        if (!event.hasTime()) {
            return;
        }

        final String worldName = event.getHandle().getWorld().getName();
        final int playerCount = event.getHandle().players().size();
        LOGGER.info(() -> "[Laminate] @InjectEvent fired — tick start in world="
                + worldName + ", players=" + playerCount);
    }
}
