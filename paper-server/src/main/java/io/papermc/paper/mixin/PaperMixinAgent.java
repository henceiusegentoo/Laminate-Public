package io.papermc.paper.mixin;

import java.lang.instrument.Instrumentation;

/**
 * A minimal Java agent whose sole responsibility is to capture the JVM's
 * {@link Instrumentation} instance and make it available to the hybrid-plugin
 * mixin bootstrap.
 *
 * <h2>How it works</h2>
 * <p>Passed via {@code -javaagent:paper-mixin-agent.jar}. The JVM calls
 * {@link #premain(String, Instrumentation)} <em>before</em> the application's
 * {@code main} method. The {@link Instrumentation} reference is stored in
 * {@link System#getProperties()} — a JVM-global {@code Hashtable} shared
 * across all class loaders.</p>
 *
 * <h2>Manifest requirements</h2>
 * <pre>
 * Premain-Class: io.papermc.paper.mixin.PaperMixinAgent
 * Agent-Class:   io.papermc.paper.mixin.PaperMixinAgent
 * Can-Retransform-Classes: true
 * Can-Redefine-Classes: true
 * </pre>
 *
 * @see LaminateMixinBootstrap
 * @see PaperMixinClassFileTransformer
 */
public final class PaperMixinAgent {

    /** Key for storing the {@link Instrumentation} in {@link System#getProperties()}. */
    static final String INSTRUMENTATION_KEY = "paper.mixin.instrumentation";

    /** System property signalling that this agent ran successfully. */
    private static final String LAUNCHER_SIGNAL_PROP = "laminate.agent.loaded";

    private PaperMixinAgent() {
    }

    /**
     * Called by the JVM when the agent is loaded at startup via {@code -javaagent}.
     *
     * @param args ignored
     * @param inst the JVM-provided {@link Instrumentation} handle
     */
    public static void premain(final String args, final Instrumentation inst) {
        System.setProperty(LAUNCHER_SIGNAL_PROP, "true");
        System.getProperties().put(INSTRUMENTATION_KEY, inst);
    }

    /**
     * Called by the JVM when the agent is loaded dynamically via the Attach API.
     *
     * @param args ignored
     * @param inst the JVM-provided {@link Instrumentation} handle
     */
    public static void agentmain(final String args, final Instrumentation inst) {
        premain(args, inst);
    }

    /**
     * Retrieves the {@link Instrumentation} instance stored by the agent.
     *
     * @return the handle, or {@code null} if the agent was never loaded
     */
    static Instrumentation getInstrumentation() {
        final Object obj = System.getProperties().get(INSTRUMENTATION_KEY);
        return (obj instanceof Instrumentation inst) ? inst : null;
    }
}
