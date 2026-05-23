package io.papermc.paper;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * Self-relaunching entry point for the Laminate server JAR.
 *
 * <h2>Purpose</h2>
 * <p>The Laminate server JAR must be started with {@code -javaagent:self} so
 * that {@link io.papermc.paper.mixin.PaperMixinAgent#premain} runs before
 * {@code main()} and captures the JVM's {@link java.lang.instrument.Instrumentation}
 * handle. The Mixin subsystem also requires four {@code --add-opens} flags to
 * access sealed JDK internals via reflection.</p>
 *
 * <p>Rather than forcing server operators to memorise these flags, this launcher
 * detects whether it is running on a properly configured JVM and, if not,
 * transparently relaunches itself with all required flags baked in:</p>
 *
 * <pre>
 * java --add-opens=java.base/java.lang=ALL-UNNAMED        \
 *      --add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
 *      --add-opens=java.base/java.io=ALL-UNNAMED           \
 *      --add-opens=java.base/java.util=ALL-UNNAMED         \
 *      -javaagent:laminate-server.jar                      \
 *      -jar      laminate-server.jar                       \
 *      [server args…]
 * </pre>
 *
 * <h2>Detection</h2>
 * <p>{@link io.papermc.paper.mixin.PaperMixinAgent#premain} sets the system
 * property {@value AGENT_LOADED_PROP} when the agent runs. On a fresh launch
 * the property is absent, so the launcher knows a relaunch is needed. On the
 * relaunched JVM the property is set before {@code main()} is called, so the
 * launcher delegates immediately to
 * {@link org.bukkit.craftbukkit.Main#main}.</p>
 *
 * <h2>Argument forwarding</h2>
 * <p>All application arguments (e.g. {@code nogui}, {@code --port 25565}) are
 * forwarded verbatim to the child process and ultimately to the Paper argument
 * parser. Existing JVM flags (heap size, system properties, …) are forwarded
 * too. Any {@code -javaagent:} entry that points to this same JAR is stripped
 * to avoid registering a duplicate agent; all other agent entries (profilers,
 * APM agents, security agents, etc.) are preserved unchanged.</p>
 *
 * <h2>Result for the operator</h2>
 * <p>A single command is all that is needed:</p>
 * <pre>{@code
 * java -jar laminate-server-1.21.11-R0.1-SNAPSHOT.jar nogui
 * }</pre>
 */
public final class LaminateLauncher {

    /**
     * System property written by
     * {@link io.papermc.paper.mixin.PaperMixinAgent#premain} to signal that
     * the Java agent has been loaded. Must stay in sync with the constant
     * used in {@code PaperMixinAgent}.
     */
    static final String AGENT_LOADED_PROP = "laminate.agent.loaded";

    private LaminateLauncher() {
    }

    /**
     * Main entry point.
     *
     * <p>If {@value AGENT_LOADED_PROP} is set the agent already ran on this
     * JVM — delegate directly to Paper. Otherwise spawn a child JVM with the
     * required flags and exit with its return code.</p>
     *
     * @param args application arguments forwarded to the Paper argument parser
     * @throws Exception if the child process cannot be started or the Paper
     *                   main class cannot be called
     */
    public static void main(final String[] args) throws Exception {
        if (System.getProperty(AGENT_LOADED_PROP) != null) {
            // Agent ran before main() on this JVM — mixin infrastructure is
            // ready. Delegate to the original bundler entry point so Paperclip
            // can set up the classpath and start the server in-process.
            invokeBundlerMain(args);
            return;
        }

        // First launch without agent: relaunch with all required flags.
        relaunchWithAgent(args);
    }

    /**
     * Reads the {@code Bundler-Main-Class} manifest attribute from this JAR
     * and invokes its {@code main(String[])} method. This delegates to the
     * original bundler entry point (Paperclip) which sets up the full server
     * classpath and starts the server in-process.
     *
     * <p>Using a manifest attribute rather than a hard-coded class name keeps
     * this launcher agnostic of the specific bundler implementation.</p>
     */
    private static void invokeBundlerMain(final String[] args) throws Exception {
        final Manifest manifest;
        try (var in = LaminateLauncher.class.getResourceAsStream("/META-INF/MANIFEST.MF")) {
            if (in == null) {
                throw new IllegalStateException(
                    "Unable to locate /META-INF/MANIFEST.MF; LaminateLauncher must be run from the packaged laminate-server.jar");
            }
            manifest = new Manifest(in);
        }
        final Attributes attrs = manifest.getMainAttributes();
        final String bundlerMain = attrs.getValue("Bundler-Main-Class");
        if (bundlerMain == null) {
            throw new IllegalStateException(
                "laminate-server.jar is missing the Bundler-Main-Class manifest attribute");
        }
        final Method main = Class.forName(bundlerMain).getMethod("main", String[].class);
        main.invoke(null, (Object) args);
    }

    /**
     * Builds a child JVM command that includes {@code --add-opens},
     * {@code -javaagent:self}, and {@code -jar self}, then replaces the
     * current process with it (inheriting stdio).
     *
     * @param appArgs the application arguments to forward
     * @throws Exception if the child process cannot be started
     */
    private static void relaunchWithAgent(final String[] appArgs) throws Exception {
        // ── Locate the Java executable ─────────────────────────────────────
        final String javaExe = ProcessHandle.current().info().command()
            .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());

        // ── Locate this JAR ────────────────────────────────────────────────
        final URI selfUri = LaminateLauncher.class
            .getProtectionDomain().getCodeSource().getLocation().toURI();
        final String selfJar = Path.of(selfUri).toAbsolutePath().toString();

        // ── Build command ──────────────────────────────────────────────────
        final List<String> cmd = new ArrayList<>();
        cmd.add(javaExe);

        // Forward existing JVM flags (heap size, -D properties, GC flags, …).
        // Only strip -javaagent: entries that point to this same JAR to avoid
        // a duplicate agent registration. All other agents (profilers, APM,
        // security agents, etc.) are preserved so operator tooling is not broken.
        for (final String jvmArg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (jvmArg.startsWith("-javaagent:")) {
                // A -javaagent: value may carry options after '=', e.g.
                // "-javaagent:/path/to/agent.jar=options". Extract just the
                // path part for the comparison.
                final String agentPath = jvmArg.substring("-javaagent:".length())
                    .split("=", 2)[0];
                // Normalise both paths before comparing so that relative vs.
                // absolute references to the same file are treated as equal.
                if (Path.of(agentPath).toAbsolutePath().normalize()
                        .equals(Path.of(selfJar).normalize())) {
                    continue; // skip — we will re-add -javaagent:selfJar below
                }
            }
            cmd.add(jvmArg);
        }

        // Required --add-opens for the SpongePowered Mixin / Quilt Loader
        // framework. These packages are sealed in Java 9+ modules; without
        // these flags the framework throws InaccessibleObjectException at
        // runtime when it tries to access JDK internals via reflection.
        cmd.add("--add-opens=java.base/java.lang=ALL-UNNAMED");
        cmd.add("--add-opens=java.base/java.lang.reflect=ALL-UNNAMED");
        cmd.add("--add-opens=java.base/java.io=ALL-UNNAMED");
        cmd.add("--add-opens=java.base/java.util=ALL-UNNAMED");

        // Load this JAR as the Java agent so PaperMixinAgent.premain() runs
        // before main(), capturing the Instrumentation handle.
        cmd.add("-javaagent:" + selfJar);

        // Re-run this same JAR as the application.
        cmd.add("-jar");
        cmd.add(selfJar);

        // Forward all application arguments (nogui, --port, --plugins, …).
        for (final String arg : appArgs) {
            cmd.add(arg);
        }

        final ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.inheritIO();
        System.exit(pb.start().waitFor());
    }
}
