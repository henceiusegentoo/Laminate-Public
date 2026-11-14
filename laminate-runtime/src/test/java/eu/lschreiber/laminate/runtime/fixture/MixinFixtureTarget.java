package eu.lschreiber.laminate.runtime.fixture;

/**
 * A trivial, self-contained "target" class used only by {@code LaminateRuntimeBootstrapTest} to
 * prove that a real hybrid plugin's mixin config can be registered with the real Sponge Mixin
 * subsystem without needing any Minecraft/Paper classes on the classpath.
 *
 * <p>This class and {@link MixinFixtureMixin} are compiled as ordinary test sources by Gradle; the
 * test reads their compiled {@code .class} bytes off the test classpath at runtime and repackages
 * them into a fixture plugin JAR, so the fixture plugin genuinely ships its own mixin + target
 * pair, matching how a real hybrid plugin would.
 */
public class MixinFixtureTarget {

    public String greet() {
        return "original";
    }
}
