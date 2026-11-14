package eu.lschreiber.laminate.launcher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RelaunchHandler")
class RelaunchHandlerTest {

    private static final Path FS_ROOT = Path.of(".").toAbsolutePath().getRoot();
    private static final String JAVA_EXE =
            FS_ROOT.resolve("jdk-21").resolve("bin").resolve("java").toString();
    private static final String SELF_JAR =
            FS_ROOT.resolve("servers").resolve("laminate-server-1.21.11.jar").toString();

    @Nested
    @DisplayName("buildCommand")
    class BuildCommand {

        @Test
        @DisplayName("puts the java executable first")
        void putsJavaExecutableFirst() {
            List<String> command = RelaunchHandler.buildCommand(
                    JAVA_EXE, SELF_JAR, List.of(), new String[] {});

            assertThat(command.get(0)).isEqualTo(JAVA_EXE);
        }

        @Test
        @DisplayName("appends all four required --add-opens flags, in order, exactly once")
        void appendsRequiredAddOpensFlags() {
            List<String> command = RelaunchHandler.buildCommand(
                    JAVA_EXE, SELF_JAR, List.of(), new String[] {});

            assertThat(command).containsSubsequence(
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                    "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-opens=java.base/java.util=ALL-UNNAMED");
        }

        @Test
        @DisplayName("adds -javaagent:<selfJar>, then -jar <selfJar>, then forwards app args")
        void addsAgentAndJarAndAppArgs() {
            List<String> command = RelaunchHandler.buildCommand(
                    JAVA_EXE, SELF_JAR, List.of(), new String[] { "--nogui", "--port", "25566" });

            assertThat(command).containsSubsequence(
                    "-javaagent:" + SELF_JAR,
                    "-jar",
                    SELF_JAR,
                    "--nogui",
                    "--port",
                    "25566");
        }

        @Test
        @DisplayName("strips a -javaagent: entry pointing at the self jar")
        void stripsSelfReferentialJavaagent() {
            List<String> command = RelaunchHandler.buildCommand(
                    JAVA_EXE,
                    SELF_JAR,
                    List.of("-javaagent:" + SELF_JAR),
                    new String[] {});

            long occurrences = command.stream()
                    .filter(arg -> arg.equals("-javaagent:" + SELF_JAR))
                    .count();
            assertThat(occurrences).isEqualTo(1);
        }

        @Test
        @DisplayName("strips a self-referential -javaagent: entry that carries agent options after '='")
        void stripsSelfReferentialJavaagentWithOptions() {
            List<String> command = RelaunchHandler.buildCommand(
                    JAVA_EXE,
                    SELF_JAR,
                    List.of("-javaagent:" + SELF_JAR + "=someOption=value"),
                    new String[] {});

            assertThat(command).doesNotContain("-javaagent:" + SELF_JAR + "=someOption=value");
            long occurrences = command.stream()
                    .filter(arg -> arg.startsWith("-javaagent:" + SELF_JAR))
                    .count();
            assertThat(occurrences).isEqualTo(1);
            assertThat(command).contains("-javaagent:" + SELF_JAR);
        }

        @Test
        @DisplayName("preserves a foreign -javaagent: entry pointing at a different jar, unchanged")
        void preservesForeignJavaagent() {
            String foreignAgent = "-javaagent:C:\\tools\\some-profiler.jar=port=9010";

            List<String> command = RelaunchHandler.buildCommand(
                    JAVA_EXE,
                    SELF_JAR,
                    List.of(foreignAgent),
                    new String[] {});

            assertThat(command).contains(foreignAgent);
            assertThat(command).contains("-javaagent:" + SELF_JAR);
        }

        @Test
        @DisplayName("forwards other JVM input args (heap size, -D properties) unchanged")
        void forwardsOtherJvmArgsUnchanged() {
            List<String> command = RelaunchHandler.buildCommand(
                    JAVA_EXE,
                    SELF_JAR,
                    List.of("-Xmx2G", "-Dsome.property=value"),
                    new String[] {});

            assertThat(command).contains("-Xmx2G", "-Dsome.property=value");
        }

        @Test
        @DisplayName("treats a relative path referring to the same self jar as self-referential")
        void treatsRelativeSelfPathAsSelfReferential() {
            String selfJarWithRedundantSeparators =
                    FS_ROOT.resolve("servers").resolve(".").resolve("laminate-server-1.21.11.jar").toString();

            List<String> command = RelaunchHandler.buildCommand(
                    JAVA_EXE,
                    SELF_JAR,
                    List.of("-javaagent:" + selfJarWithRedundantSeparators),
                    new String[] {});

            long occurrences = command.stream()
                    .filter(arg -> arg.startsWith("-javaagent:"))
                    .count();
            assertThat(occurrences).isEqualTo(1);
            assertThat(command).contains("-javaagent:" + SELF_JAR);
        }

        @Test
        @DisplayName("full command shape end-to-end for a realistic mixed input")
        void fullCommandShapeRealisticInput() {
            List<String> command = RelaunchHandler.buildCommand(
                    JAVA_EXE,
                    SELF_JAR,
                    List.of("-Xmx4G", "-javaagent:" + SELF_JAR, "-javaagent:C:\\tools\\apm.jar"),
                    new String[] { "--nogui" });

            assertThat(command).containsExactly(
                    JAVA_EXE,
                    "-Xmx4G",
                    "-javaagent:C:\\tools\\apm.jar",
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                    "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-opens=java.base/java.util=ALL-UNNAMED",
                    "-javaagent:" + SELF_JAR,
                    "-jar",
                    SELF_JAR,
                    "--nogui");
        }
    }
}
