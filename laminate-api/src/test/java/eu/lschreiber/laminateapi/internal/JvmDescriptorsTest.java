package eu.lschreiber.laminateapi.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JvmDescriptors")
class JvmDescriptorsTest {

    @Nested
    @DisplayName("fromClass")
    class FromClass {

        @Test
        @DisplayName("maps void to V")
        void mapsVoidToV() {
            // Arrange & Act
            final String result = JvmDescriptors.fromClass(void.class);

            // Assert
            assertThat(result).isEqualTo("V");
        }

        @Test
        @DisplayName("maps all primitive types correctly")
        void mapsAllPrimitiveTypes() {
            assertThat(JvmDescriptors.fromClass(boolean.class)).isEqualTo("Z");
            assertThat(JvmDescriptors.fromClass(byte.class)).isEqualTo("B");
            assertThat(JvmDescriptors.fromClass(char.class)).isEqualTo("C");
            assertThat(JvmDescriptors.fromClass(short.class)).isEqualTo("S");
            assertThat(JvmDescriptors.fromClass(int.class)).isEqualTo("I");
            assertThat(JvmDescriptors.fromClass(long.class)).isEqualTo("J");
            assertThat(JvmDescriptors.fromClass(float.class)).isEqualTo("F");
            assertThat(JvmDescriptors.fromClass(double.class)).isEqualTo("D");
        }

        @Test
        @DisplayName("maps reference type to L-descriptor")
        void mapsReferenceType() {
            // Arrange & Act
            final String result = JvmDescriptors.fromClass(String.class);

            // Assert
            assertThat(result).isEqualTo("Ljava/lang/String;");
        }

        @Test
        @DisplayName("maps nested class with internal name separator")
        void mapsNestedClass() {
            // Arrange & Act
            final String result = JvmDescriptors.fromClass(java.util.Map.Entry.class);

            // Assert
            assertThat(result).isEqualTo("Ljava/util/Map$Entry;");
        }

        @Test
        @DisplayName("maps primitive array to [descriptor")
        void mapsPrimitiveArray() {
            // Arrange & Act
            final String result = JvmDescriptors.fromClass(int[].class);

            // Assert
            assertThat(result).isEqualTo("[I");
        }

        @Test
        @DisplayName("maps reference array to [L-descriptor")
        void mapsReferenceArray() {
            // Arrange & Act
            final String result = JvmDescriptors.fromClass(String[].class);

            // Assert
            assertThat(result).isEqualTo("[Ljava/lang/String;");
        }

        @Test
        @DisplayName("maps multi-dimensional array")
        void mapsMultiDimensionalArray() {
            // Arrange & Act
            final String result = JvmDescriptors.fromClass(int[][].class);

            // Assert
            assertThat(result).isEqualTo("[[I");
        }
    }
}
