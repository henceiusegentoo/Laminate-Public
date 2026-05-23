package eu.lschreiber.laminateapi.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InjectEventProcessor")
class InjectEventProcessorTest {

    @Nested
    @DisplayName("isVoidReturn")
    class IsVoidReturn {

        @Test
        @DisplayName("returns true for void descriptor")
        void voidDescriptor() {
            assertThat(InjectEventProcessor.isVoidReturn(
                "tick(Ljava/util/function/BooleanSupplier;)V")).isTrue();
        }

        @Test
        @DisplayName("returns false for non-void descriptor")
        void nonVoidDescriptor() {
            assertThat(InjectEventProcessor.isVoidReturn(
                "getLevel()Lnet/minecraft/server/level/ServerLevel;")).isFalse();
        }

        @Test
        @DisplayName("returns true for bare method name (no descriptor) — defaults to void")
        void bareMethodNameDefaultsToVoid() {
            // method = "tick" without a JVM descriptor: no ')' present.
            // The processor cannot determine the return type; void is assumed
            // because the vast majority of NMS injection targets are void.
            assertThat(InjectEventProcessor.isVoidReturn("tick")).isTrue();
        }

        @Test
        @DisplayName("returns true for no-arg void descriptor")
        void noArgVoid() {
            assertThat(InjectEventProcessor.isVoidReturn("run()V")).isTrue();
        }

        @Test
        @DisplayName("returns false for boolean-return descriptor")
        void booleanReturn() {
            assertThat(InjectEventProcessor.isVoidReturn("hasTime()Z")).isFalse();
        }
    }

    // ...existing code...

    @Nested
    @DisplayName("sanitize")
    class Sanitize {

        @Test
        @DisplayName("replaces dollar signs with underscores")
        void replacesDollarSigns() {
            // Arrange & Act
            final String result = InjectEventProcessor.sanitize("test$method");

            // Assert
            assertThat(result).isEqualTo("test_method");
        }

        @Test
        @DisplayName("replaces dots with underscores")
        void replacesDots() {
            // Arrange & Act
            final String result = InjectEventProcessor.sanitize("com.example");

            // Assert
            assertThat(result).isEqualTo("com_example");
        }

        @Test
        @DisplayName("leaves clean names unchanged")
        void leavesCleanNamesUnchanged() {
            // Arrange & Act
            final String result = InjectEventProcessor.sanitize("cleanName");

            // Assert
            assertThat(result).isEqualTo("cleanName");
        }

        @Test
        @DisplayName("handles multiple special characters")
        void handlesMultipleSpecialChars() {
            // Arrange & Act
            final String result = InjectEventProcessor.sanitize("a$b.c$d");

            // Assert
            assertThat(result).isEqualTo("a_b_c_d");
        }
    }

    @Nested
    @DisplayName("resolveConfigFileName")
    class ResolveConfigFileName {

        @Test
        @DisplayName("strips trailing .mixins and uses preceding segment")
        void stripsTrailingMixins() {
            final String result = InjectEventProcessor.resolveConfigFileName(
                "eu.lschreiber.testplugin.mixins");

            assertThat(result).isEqualTo("mixins.testplugin.generated.json");
        }

        @Test
        @DisplayName("strips trailing .mixin and uses preceding segment")
        void stripsTrailingMixin() {
            final String result = InjectEventProcessor.resolveConfigFileName(
                "com.example.myplugin.mixin");

            assertThat(result).isEqualTo("mixins.myplugin.generated.json");
        }

        @Test
        @DisplayName("uses last segment when no .mixins suffix present")
        void usesLastSegmentWithoutSuffix() {
            final String result = InjectEventProcessor.resolveConfigFileName(
                "com.example.myplugin");

            assertThat(result).isEqualTo("mixins.myplugin.generated.json");
        }

        @Test
        @DisplayName("handles deep package hierarchy")
        void handlesDeepPackage() {
            final String result = InjectEventProcessor.resolveConfigFileName(
                "org.github.user.coolplugin.core.mixins");

            assertThat(result).isEqualTo("mixins.core.generated.json");
        }

        @Test
        @DisplayName("handles two-segment package with mixins suffix")
        void handlesTwoSegmentWithSuffix() {
            final String result = InjectEventProcessor.resolveConfigFileName(
                "myplugin.mixins");

            assertThat(result).isEqualTo("mixins.myplugin.generated.json");
        }

        @Test
        @DisplayName("handles single-segment package")
        void handlesSingleSegment() {
            final String result = InjectEventProcessor.resolveConfigFileName(
                "myplugin");

            assertThat(result).isEqualTo("mixins.myplugin.generated.json");
        }

        @Test
        @DisplayName("returns fallback for empty package")
        void returnsFallbackForEmptyPackage() {
            final String result = InjectEventProcessor.resolveConfigFileName("");

            assertThat(result).isEqualTo("mixins.generated.json");
        }
    }
}
