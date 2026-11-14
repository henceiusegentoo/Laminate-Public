package eu.lschreiber.laminate.launcher;

import eu.lschreiber.laminate.bridge.LaminateLog;
import eu.lschreiber.laminate.bridge.LaminateLog.Level;
import eu.lschreiber.laminate.bridge.LaminateLogMessages;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Relaunches the current JVM with the agent and opens Laminate needs. */
public final class RelaunchHandler {

    private static final LaminateLog LOGGER = LaminateLog.getLogger("laminate.launcher.RelaunchHandler");

    private static final String JAVAAGENT_PREFIX = "-javaagent:";

    private RelaunchHandler() {
    }

    public static void relaunch(String[] appArgs) throws Exception {
        final List<String> command = buildRelaunchCommand(appArgs);

        LOGGER.log(Level.INFO, LaminateLogMessages.RELAUNCHING);
        LOGGER.log(Level.DEBUG, String.format(LaminateLogMessages.RELAUNCH_COMMAND, String.join(" ", command)));

        System.exit(startChildProcess(command).waitFor());
    }

    private static List<String> buildRelaunchCommand(final String[] appArgs) throws Exception {
        return buildCommand(
                resolveJavaExecutable(),
                resolveSelfJar(),
                ManagementFactory.getRuntimeMXBean().getInputArguments(),
                appArgs);
    }

    private static Process startChildProcess(final List<String> command) throws Exception {
        final ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.inheritIO();
        return processBuilder.start();
    }

    static String resolveJavaExecutable() {
        return ProcessHandle.current().info().command()
                .orElseGet(() -> Path.of(System.getProperty("java.home"), "bin", "java").toString());
    }

    static String resolveSelfJar() throws Exception {
        URI selfUri = RelaunchHandler.class.getProtectionDomain().getCodeSource().getLocation().toURI();
        return Path.of(selfUri).toAbsolutePath().toString();
    }

    static List<String> buildCommand(
            final String javaExecutable,
            final String selfJar,
            final List<String> existingJvmArgs,
            final String[] appArgs) {
        final List<String> command = new ArrayList<>();
        command.add(javaExecutable);

        final Path normalizedSelfJar = Path.of(selfJar).toAbsolutePath().normalize();
        for (final String jvmArg : existingJvmArgs) {
            if (jvmArg.startsWith(JAVAAGENT_PREFIX) && isSelfReferential(jvmArg, normalizedSelfJar)) {
                continue;
            }
            command.add(jvmArg);
        }

        command.add("--add-opens=java.base/java.lang=ALL-UNNAMED");
        command.add("--add-opens=java.base/java.lang.reflect=ALL-UNNAMED");
        command.add("--add-opens=java.base/java.io=ALL-UNNAMED");
        command.add("--add-opens=java.base/java.util=ALL-UNNAMED");

        command.add(JAVAAGENT_PREFIX + selfJar);

        command.add("-jar");
        command.add(selfJar);

        command.addAll(List.of(appArgs));

        return command;
    }

    private static boolean isSelfReferential(final String javaagentArg, final Path normalizedSelfJar) {
        final String agentPath = javaagentArg.substring(JAVAAGENT_PREFIX.length()).split("=", 2)[0];
        return Path.of(agentPath).toAbsolutePath().normalize().equals(normalizedSelfJar);
    }
}
