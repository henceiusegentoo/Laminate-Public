package eu.lschreiber.laminate.agent;

import java.lang.instrument.Instrumentation;

/** Captures the JVM {@link Instrumentation} handle for later bootstrap stages. */
public final class LaminateAgent {

    private static final String INSTRUMENTATION_PROPERTY = "laminate.instrumentation";

    private static final String AGENT_LOADED_PROPERTY = "laminate.agent.loaded";

    private LaminateAgent() {
    }

    /** Stores the instrumentation handle during startup loading. */
    public static void premain(String agentArgs, Instrumentation inst) {
        System.getProperties().put(INSTRUMENTATION_PROPERTY, inst);
        System.setProperty(AGENT_LOADED_PROPERTY, "true");
    }

    /** Stores the instrumentation handle during attach loading. */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        premain(agentArgs, inst);
    }

    /** Returns the captured instrumentation handle. */
    public static Instrumentation getInstrumentation() {
        Object stashed = System.getProperties().get(INSTRUMENTATION_PROPERTY);
        if (!(stashed instanceof Instrumentation inst)) {
            throw new IllegalStateException(
                    "No Instrumentation available: LaminateAgent has not been loaded as a Java "
                            + "agent (expected system property '" + INSTRUMENTATION_PROPERTY
                            + "' to hold an Instrumentation instance, found: " + stashed + ").");
        }
        return inst;
    }
}
