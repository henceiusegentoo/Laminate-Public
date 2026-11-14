package eu.lschreiber.testplugin.mixins;

import eu.lschreiber.laminateapi.accessor.GenerateAccessor;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Adds a demo accessor method to {@link MinecraftServer}. */
@Mixin(MinecraftServer.class)
public final class ServerMixin {

    @GenerateAccessor(name = "write")
    @Unique
    public void testPlugin$write(final String msg) {
        System.out.println("[test-plugin] write() injected via mixin: " + msg);
    }
}
