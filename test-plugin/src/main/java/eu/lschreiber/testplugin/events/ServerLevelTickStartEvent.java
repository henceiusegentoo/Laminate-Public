package eu.lschreiber.testplugin.events;

import net.minecraft.server.level.ServerLevel;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.function.BooleanSupplier;

/** Fired at the start of each {@code ServerLevel.tick(BooleanSupplier)} call. */
public final class ServerLevelTickStartEvent extends Event {

    private static final HandlerList HANDLER_LIST = new HandlerList();

    private final ServerLevel handle;
    private final BooleanSupplier hasTime;

    /** Called by the generated mixin companion. */
    public ServerLevelTickStartEvent(@NotNull ServerLevel handle,
                                     @NotNull BooleanSupplier hasTime) {
        this.handle = handle;
        this.hasTime = hasTime;
    }

    /** Returns the ticking NMS level. */
    @NotNull
    public ServerLevel getHandle() {
        return this.handle;
    }

    /** Returns whether the server has time budget for this tick. */
    public boolean hasTime() {
        return this.hasTime.getAsBoolean();
    }

    /** Returns the underlying time-budget supplier. */
    @NotNull
    public BooleanSupplier getHasTimeSupplier() {
        return this.hasTime;
    }

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return HANDLER_LIST;
    }

    @NotNull
    public static HandlerList getHandlerList() {
        return HANDLER_LIST;
    }
}
