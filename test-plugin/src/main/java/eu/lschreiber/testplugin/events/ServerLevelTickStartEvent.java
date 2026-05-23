package eu.lschreiber.testplugin.events;

import net.minecraft.server.level.ServerLevel;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.function.BooleanSupplier;

/**
 * Fired at the very start of each {@code ServerLevel.tick(BooleanSupplier)}
 * call, before any game logic runs for that level.
 *
 * <p>Bridged from NMS via
 * {@code @InjectEvent(method = "tick", at = At.HEAD)}.</p>
 */
public final class ServerLevelTickStartEvent extends Event {

    private static final HandlerList HANDLER_LIST = new HandlerList();

    private final ServerLevel handle;
    private final BooleanSupplier hasTime;

    /**
     * Constructor called by the generated Mixin companion class.
     * Convention: first parameter is the Mixin target ({@code ServerLevel}),
     * followed by the marker method's parameters in declaration order.
     *
     * @param handle  the NMS level that is ticking
     * @param hasTime supplier indicating whether the server has time budget
     */
    public ServerLevelTickStartEvent(@NotNull ServerLevel handle,
                                     @NotNull BooleanSupplier hasTime) {
        this.handle = handle;
        this.hasTime = hasTime;
    }

    /** Returns the raw NMS {@code ServerLevel} that is ticking. */
    @NotNull
    public ServerLevel getHandle() {
        return this.handle;
    }

    /**
     * Returns {@code true} if the server currently has spare time budget
     * for this tick, exactly as reported by the {@code BooleanSupplier}
     * passed into {@code ServerLevel.tick()}.
     */
    public boolean hasTime() {
        return this.hasTime.getAsBoolean();
    }

    /**
     * Returns the raw {@link BooleanSupplier} for cases where you need to
     * defer the check or pass it along.
     */
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
