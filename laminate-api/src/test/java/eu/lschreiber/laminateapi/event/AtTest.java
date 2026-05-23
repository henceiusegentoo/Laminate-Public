package eu.lschreiber.laminateapi.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("At")
class AtTest {

    @Test
    @DisplayName("contains exactly four injection points")
    void containsFourInjectionPoints() {
        assertThat(At.values()).containsExactly(At.HEAD, At.TAIL, At.RETURN, At.INVOKE);
    }

    @Test
    @DisplayName("name() matches Mixin @At value convention")
    void nameMatchesMixinConvention() {
        assertThat(At.HEAD.name()).isEqualTo("HEAD");
        assertThat(At.TAIL.name()).isEqualTo("TAIL");
        assertThat(At.RETURN.name()).isEqualTo("RETURN");
        assertThat(At.INVOKE.name()).isEqualTo("INVOKE");
    }
}
