package eu.lschreiber.laminate.bridge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("LaminateBridge")
class LaminateBridgeTest {

    /**
     * {@link LaminateBridge} is deliberately global static state (that's the whole point - it
     * must be readable from any classloader without a shared instance to pass around), so tests
     * that record a result must reset it afterward to avoid leaking state into other tests. There
     * is no production reset method ({@code recordResult} is meant to be called exactly once by
     * real callers), so tests reach in via reflection rather than adding test-only API surface to
     * the class.
     */
    @AfterEach
    void resetBridgeState() throws Exception {
        Field resultField = LaminateBridge.class.getDeclaredField("RESULT");
        resultField.setAccessible(true);
        ((AtomicReference<?>) resultField.get(null)).set(null);
    }

    @Test
    @DisplayName("isActive() is false before any result has been recorded")
    void isActiveFalseBeforeRecording() {
        assertThat(LaminateBridge.isActive()).isFalse();
    }

    @Test
    @DisplayName("loaded/failed sets are empty before any result has been recorded")
    void emptySetsBeforeRecording() {
        assertThat(LaminateBridge.loadedHybridPluginNames()).isEmpty();
        assertThat(LaminateBridge.failedHybridPluginNames()).isEmpty();
    }

    @Test
    @DisplayName("recordResult then read round-trips the loaded set unchanged")
    void roundTripsLoadedSet() {
        LaminateBridge.recordResult(Set.of("PluginA", "PluginB"), Set.of());

        assertThat(LaminateBridge.loadedHybridPluginNames()).containsExactlyInAnyOrder("PluginA", "PluginB");
    }

    @Test
    @DisplayName("recordResult then read round-trips the failed set, lower-cased")
    void roundTripsFailedSetLowerCased() {
        LaminateBridge.recordResult(Set.of(), Set.of("PluginC", "PLUGIND"));

        assertThat(LaminateBridge.failedHybridPluginNames()).containsExactlyInAnyOrder("pluginc", "plugind");
    }

    @Test
    @DisplayName("loaded set is NOT lower-cased (only the failed set is)")
    void loadedSetPreservesCase() {
        LaminateBridge.recordResult(Set.of("MixedCasePlugin"), Set.of());

        assertThat(LaminateBridge.loadedHybridPluginNames()).containsExactly("MixedCasePlugin");
    }

    @Test
    @DisplayName("isActive() is true after recordResult, even with two empty sets")
    void isActiveTrueAfterRecordingEvenIfEmpty() {
        LaminateBridge.recordResult(Set.of(), Set.of());

        assertThat(LaminateBridge.isActive()).isTrue();
    }

    @Test
    @DisplayName("loadedHybridPluginNames() returns an unmodifiable view")
    void loadedSetIsUnmodifiable() {
        LaminateBridge.recordResult(Set.of("PluginA"), Set.of());

        Set<String> loaded = LaminateBridge.loadedHybridPluginNames();

        assertThatThrownBy(() -> loaded.add("Intruder"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("failedHybridPluginNames() returns an unmodifiable view")
    void failedSetIsUnmodifiable() {
        LaminateBridge.recordResult(Set.of(), Set.of("PluginB"));

        Set<String> failed = LaminateBridge.failedHybridPluginNames();

        assertThatThrownBy(() -> failed.add("intruder"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("mutating the caller's input sets after recordResult does not affect internal state")
    void defensiveCopyProtectsAgainstCallerMutation() {
        Set<String> loadedInput = new HashSet<>(Set.of("PluginA"));
        Set<String> failedInput = new HashSet<>(Set.of("PluginB"));

        LaminateBridge.recordResult(loadedInput, failedInput);
        loadedInput.add("SneakyLateAddition");
        failedInput.clear();

        assertThat(LaminateBridge.loadedHybridPluginNames()).containsExactly("PluginA");
        assertThat(LaminateBridge.failedHybridPluginNames()).containsExactly("pluginb");
    }

    @Test
    @DisplayName("a later recordResult call replaces the previously recorded result")
    void laterRecordResultReplacesEarlierOne() {
        LaminateBridge.recordResult(Set.of("First"), Set.of());
        LaminateBridge.recordResult(Set.of("Second"), Set.of());

        assertThat(LaminateBridge.loadedHybridPluginNames()).containsExactly("Second");
    }
}
