package eu.lschreiber.testplugin.mixins;

import eu.lschreiber.laminateapi.event.At;
import eu.lschreiber.laminateapi.event.InjectEvent;
import eu.lschreiber.testplugin.events.ServerLevelTickStartEvent;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.function.BooleanSupplier;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {

    /**
     * Fires {@link ServerLevelTickStartEvent} at the start of each
     * {@code ServerLevel.tick(BooleanSupplier)} call.
     */
    @Unique
    @InjectEvent(
            method = "tick",
            at = At.HEAD,
            event = ServerLevelTickStartEvent.class
    )
    private void testPlugin$onTick(BooleanSupplier hasTime) {
        // Marker — the @InjectEvent processor generates the actual injection.
    }
}
