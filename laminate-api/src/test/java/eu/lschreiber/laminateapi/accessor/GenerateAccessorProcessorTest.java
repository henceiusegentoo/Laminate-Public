package eu.lschreiber.laminateapi.accessor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GenerateAccessorProcessor")
class GenerateAccessorProcessorTest {

    @Nested
    @DisplayName("deriveAccessorPackage")
    class DeriveAccessorPackage {

        @Test
        @DisplayName("empty package returns 'accessor'")
        void emptyPackageReturnsAccessor() {
            final String result = GenerateAccessorProcessor.deriveAccessorPackage("");

            assertThat(result).isEqualTo("accessor");
        }

        @Test
        @DisplayName("single-segment package returns 'accessor'")
        void singleSegmentReturnsAccessor() {
            final String result = GenerateAccessorProcessor.deriveAccessorPackage("mixins");

            assertThat(result).isEqualTo("accessor");
        }

        @Test
        @DisplayName("replaces last segment with 'accessor'")
        void replacesLastSegment() {
            final String result = GenerateAccessorProcessor.deriveAccessorPackage("com.example.mixins");

            assertThat(result).isEqualTo("com.example.accessor");
        }

        @Test
        @DisplayName("handles deeply nested packages")
        void handlesDeeplyNestedPackages() {
            final String result = GenerateAccessorProcessor.deriveAccessorPackage("org.a.b.c.mixins");

            assertThat(result).isEqualTo("org.a.b.c.accessor");
        }
    }
}
