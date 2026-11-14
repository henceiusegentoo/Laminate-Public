package eu.lschreiber.laminate.bridge;

/** Shared format strings for logs emitted through {@link LaminateLog}. */
public final class LaminateLogMessages {

    private LaminateLogMessages() {
    }

    // LaminateRuntimeBootstrap
    public static final String BOOTSTRAP_FAILED_SUMMARY =
            "[Laminate] Hybrid mixin bootstrap failed for %d plugin(s). They will be disabled.";
    public static final String HYBRID_PLUGIN_BOOTSTRAP_FAILED =
            "[Laminate] Hybrid mixin bootstrap failed for plugin %s (%s). Plugin will be disabled.";
    public static final String DISCOVERED_HYBRID_PLUGIN =
            "[Laminate] Discovered hybrid plugin %s (%d mixin config(s)).";
    public static final String INJECTED_MIXIN_CONFIGS =
            "[Laminate] Injected %d mixin config(s) for hybrid plugin %s";
    public static final String INJECTED_CORE_MIXINS_CONFIG =
            "[Laminate] Injected core mixins config (%s)";
    public static final String FAILED_CORE_MIXINS_CONFIG =
            "[Laminate] Failed to register core mixins config (%s).";
    public static final String NO_INSTRUMENTATION =
            "[Laminate] No Instrumentation available — mixin transformations will NOT be applied to classes at load time.";
    public static final String INSTALLED_TRANSFORMER =
            "[Laminate] Installed LaminateMixinTransformer.";
    public static final String MIXIN_TRANSFORMATIONS_ACTIVE =
            "[Laminate] Hybrid mixin transformations are now active (%d plugin(s) loaded, %d failed).";
    public static final String WATCHDOG_TIMEOUT =
            "[Laminate] No real game class load detected within %ds of startup (%d hybrid plugin(s) discovered) — "
                    + "hybrid mixin transformations were never activated. This usually means a Paper/paperclip "
                    + "update changed how the server's classloader is constructed.";
    public static final String REPUBLISHED_BRIDGE =
            "[Laminate] Republished the hybrid mixin bootstrap result into bundlerLoader's own LaminateBridge class.";
    public static final String REPUBLISH_FAILED =
            "[Laminate] Could not republish the hybrid mixin bootstrap result into bundlerLoader's own LaminateBridge copy.";
    public static final String OBTAIN_TRANSFORMER_NULL =
            "[Laminate] MixinServiceKnot returned a null transformer — mixin transformations will not be applied.";
    public static final String OBTAIN_TRANSFORMER_FAILED =
            "[Laminate] Failed to obtain IMixinTransformer from MixinServiceKnot";
    public static final String RETRANSFORMING =
            "[Laminate] Retransforming %d already-loaded classes for mixin support...";
    public static final String RETRANSFORMED =
            "[Laminate] Retransformed %d classes";
    public static final String RETRANSFORM_UNSUPPORTED =
            "[Laminate] Cannot retransform %s (schema change required — class was loaded before mixin bootstrap)";
    public static final String RETRANSFORM_FAILED =
            "[Laminate] Failed to retransform %s";

    // PluginJarCollector
    public static final String FAILED_LIST_PLUGIN_DIR =
            "[Laminate] Failed to list plugin directory %s for hybrid mixin discovery.";

    // LaminateMixinTransformer
    public static final String MIXIN_TRANSFORMED_CLASS =
            "[Laminate] Mixin-transformed class: %s";
    public static final String MIXIN_TRANSFORM_FAILED =
            "[Laminate] Mixin transformation of %s failed — class will load untransformed";

    // LaminateMain
    public static final String AGENT_LOADED =
            "[Laminate] Agent loaded; Instrumentation available.";
    public static final String HYBRID_BOOTSTRAP_FAILED =
            "[Laminate] Hybrid mixin bootstrap failed — mixin plugins will not work.";

    // RelaunchHandler
    public static final String RELAUNCHING =
            "[Laminate] Relaunching with the required agent + JVM flags.";
    public static final String RELAUNCH_COMMAND =
            "[Laminate] Relaunch command: %s";
}
