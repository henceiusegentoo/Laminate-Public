package eu.lschreiber.laminateapi.accessor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("LaminateAccessors")
class LaminateAccessorsTest {

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("throws on null accessor interface")
        void throwsOnNullInterface() {
            assertThatThrownBy(() -> LaminateAccessors.create(null, new Object(), new String[0]))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("throws on null target")
        void throwsOnNullTarget() {
            assertThatThrownBy(() -> LaminateAccessors.create(Runnable.class, null, new String[0]))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("throws on null method mappings")
        void throwsOnNullMappings() {
            assertThatThrownBy(() -> LaminateAccessors.create(Runnable.class, new Object(), null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("throws on odd-length method mappings")
        void throwsOnOddLengthMappings() {
            // Arrange
            final String[] oddMappings = {"one"};

            // Act & Assert
            assertThatThrownBy(() -> LaminateAccessors.create(Runnable.class, new Object(), oddMappings))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("even number");
        }

        @Test
        @DisplayName("creates a proxy implementing the accessor interface")
        void createsProxyImplementingInterface() {
            // Arrange
            final String[] mappings = new String[0];
            final Object target = new Object();

            // Act
            final Runnable result = LaminateAccessors.create(Runnable.class, target, mappings);

            // Assert
            assertThat(result).isInstanceOf(Runnable.class);
        }

        @Test
        @DisplayName("proxy toString returns descriptive string")
        void proxyToStringIsDescriptive() {
            // Arrange
            final Object target = "test-target";

            // Act
            final Runnable result = LaminateAccessors.create(Runnable.class, target, new String[0]);

            // Assert
            assertThat(result.toString()).startsWith("LaminateAccessorProxy[");
        }

        @Test
        @DisplayName("proxy hashCode returns identity hash")
        void proxyHashCodeReturnsIdentityHash() {
            // Arrange
            final Object target = new Object();

            // Act
            final Runnable result = LaminateAccessors.create(Runnable.class, target, new String[0]);

            // Assert
            assertThat(result.hashCode()).isEqualTo(System.identityHashCode(result));
        }

        @Test
        @DisplayName("proxy equals uses reference equality")
        void proxyEqualsUsesReferenceEquality() {
            // Arrange
            final Object target = new Object();
            final Runnable proxy = LaminateAccessors.create(Runnable.class, target, new String[0]);

            // Act & Assert
            assertThat(proxy.equals(proxy)).isTrue();
            assertThat(proxy.equals(new Object())).isFalse();
        }
    }
}
