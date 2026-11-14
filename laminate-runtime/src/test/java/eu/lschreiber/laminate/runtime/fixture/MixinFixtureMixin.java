package eu.lschreiber.laminate.runtime.fixture;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * A trivial, real {@code @Mixin} used by {@code LaminateRuntimeBootstrapTest} to prove that
 * {@code LaminateRuntimeBootstrap} can drive a real hybrid plugin's mixin config through the actual
 * Sponge Mixin subsystem (config registration + phase advance) without any Minecraft/Paper classes
 * on the classpath. See {@link MixinFixtureTarget} for the fixture's target class.
 */
@Mixin(MixinFixtureTarget.class)
public class MixinFixtureMixin {

    @Overwrite
    public String greet() {
        return "mixed";
    }
}
