package eu.lschreiber.laminate.launcher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PluginArguments")
class PluginArgumentsTest {

    @Nested
    @DisplayName("--plugins")
    class Plugins {

        @Test
        @DisplayName("defaults to \"plugins\" when absent")
        void defaultsWhenAbsent() {
            PluginArguments parsed = PluginArguments.parse(new String[] {});

            assertThat(parsed.pluginDir()).isEqualTo(Path.of("plugins"));
        }

        @Test
        @DisplayName("takes the single given value")
        void singleValue() {
            PluginArguments parsed = PluginArguments.parse(new String[] { "--plugins", "custom-plugins" });

            assertThat(parsed.pluginDir()).isEqualTo(Path.of("custom-plugins"));
        }

        @Test
        @DisplayName("last occurrence wins if given more than once")
        void lastOccurrenceWins() {
            PluginArguments parsed = PluginArguments.parse(
                    new String[] { "--plugins", "first", "--plugins", "second" });

            assertThat(parsed.pluginDir()).isEqualTo(Path.of("second"));
        }

        @Test
        @DisplayName("throws if the flag is given with no following value")
        void throwsWhenValueMissing() {
            assertThatThrownBy(() -> PluginArguments.parse(new String[] { "--plugins" }))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("--add-plugin")
    class AddPlugin {

        @Test
        @DisplayName("empty when absent")
        void emptyWhenAbsent() {
            PluginArguments parsed = PluginArguments.parse(new String[] {});

            assertThat(parsed.addPluginFiles()).isEmpty();
        }

        @Test
        @DisplayName("collects a single occurrence")
        void singleOccurrence() {
            PluginArguments parsed = PluginArguments.parse(new String[] { "--add-plugin", "one.jar" });

            assertThat(parsed.addPluginFiles()).containsExactly(Path.of("one.jar"));
        }

        @Test
        @DisplayName("collects repeated occurrences, in order")
        void repeatedOccurrences() {
            PluginArguments parsed = PluginArguments.parse(new String[] {
                    "--add-plugin", "one.jar",
                    "--add-plugin", "two.jar",
                    "--add-plugin", "three.jar",
            });

            assertThat(parsed.addPluginFiles())
                    .containsExactly(Path.of("one.jar"), Path.of("two.jar"), Path.of("three.jar"));
        }
    }

    @Nested
    @DisplayName("--add-plugin-dir")
    class AddPluginDir {

        @Test
        @DisplayName("empty when absent")
        void emptyWhenAbsent() {
            PluginArguments parsed = PluginArguments.parse(new String[] {});

            assertThat(parsed.addPluginDirs()).isEmpty();
        }

        @Test
        @DisplayName("collects a single occurrence")
        void singleOccurrence() {
            PluginArguments parsed = PluginArguments.parse(new String[] { "--add-plugin-dir", "extra" });

            assertThat(parsed.addPluginDirs()).containsExactly(Path.of("extra"));
        }

        @Test
        @DisplayName("collects repeated occurrences, in order")
        void repeatedOccurrences() {
            PluginArguments parsed = PluginArguments.parse(new String[] {
                    "--add-plugin-dir", "one",
                    "--add-plugin-dir", "two",
            });

            assertThat(parsed.addPluginDirs()).containsExactly(Path.of("one"), Path.of("two"));
        }
    }

    @Nested
    @DisplayName("mixed / unrelated arguments")
    class Mixed {

        @Test
        @DisplayName("parses all three flags together and ignores unrelated arguments")
        void allThreeTogetherIgnoringUnrelated() {
            PluginArguments parsed = PluginArguments.parse(new String[] {
                    "--nogui",
                    "--plugins", "myplugins",
                    "--add-plugin", "a.jar",
                    "--port", "25566",
                    "--add-plugin-dir", "extra-dir",
                    "--add-plugin", "b.jar",
            });

            assertThat(parsed.pluginDir()).isEqualTo(Path.of("myplugins"));
            assertThat(parsed.addPluginFiles()).containsExactly(Path.of("a.jar"), Path.of("b.jar"));
            assertThat(parsed.addPluginDirs()).containsExactly(Path.of("extra-dir"));
        }
    }
}
