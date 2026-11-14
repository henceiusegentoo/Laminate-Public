package eu.lschreiber.laminate.runtime;

import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LaminateQuiltLauncher}'s pinned-game-loader holder — the one piece of this
 * task that is meaningfully testable without a real Quilt/Mixin environment (everything else is a
 * thin adapter over {@code QuiltLauncherBase} that only makes sense wired into a live bootstrap;
 * see the phase 3 task 3 report for what remains verified only by Task 7's live test).
 *
 * <p>Does not construct a {@link LaminateQuiltLauncher} instance at all — {@link
 * LaminateQuiltLauncher#pinGameLoaderIfAbsent} and {@link LaminateQuiltLauncher#pinnedGameLoader()}
 * are static, so the first-write-wins contract can be exercised directly against those
 * package-private static members. This test lives in the same package
 * ({@code eu.lschreiber.laminate.runtime}) as {@code LaminateQuiltLauncher} specifically so it can
 * call them directly, without reflection, the same way {@code LaminateMixinTransformer} and the
 * future {@code LaminateRuntimeBootstrap} do.
 */
@DisplayName("LaminateQuiltLauncher pinned game loader")
class LaminateQuiltLauncherPinnedGameLoaderTest {

    /**
     * The pinned-game-loader field is deliberately process-wide static state (it must be
     * reachable from {@code LaminateMixinTransformer} regardless of which classloader is
     * running), so tests that pin a loader must reset it afterward to avoid leaking state into
     * other tests. There's no production reset method ({@code pinGameLoaderIfAbsent} is meant to
     * fire exactly once for real, on whichever loader shows up first), so tests reach in via
     * reflection - matching this repo's {@code LaminateBridgeTest} convention for the same kind of
     * static holder.
     */
    @AfterEach
    void resetPinnedGameLoader() throws Exception {
        Field field = LaminateQuiltLauncher.class.getDeclaredField("pinnedGameLoader");
        field.setAccessible(true);
        ((AtomicReference<?>) field.get(null)).set(null);
    }

    @Test
    @DisplayName("pinnedGameLoader() is null before any loader has been pinned")
    void nullBeforePinning() {
        assertThat(LaminateQuiltLauncher.pinnedGameLoader()).isNull();
    }

    @Test
    @DisplayName("first non-null loader passed to pinGameLoaderIfAbsent is recorded")
    void firstLoaderIsPinned() {
        ClassLoader first = newDistinctClassLoader();

        LaminateQuiltLauncher.pinGameLoaderIfAbsent(first);

        assertThat(LaminateQuiltLauncher.pinnedGameLoader()).isSameAs(first);
    }

    @Test
    @DisplayName("a second, different loader does not overwrite the first pin")
    void secondLoaderDoesNotOverwritePin() {
        ClassLoader first = newDistinctClassLoader();
        ClassLoader second = newDistinctClassLoader();

        LaminateQuiltLauncher.pinGameLoaderIfAbsent(first);
        LaminateQuiltLauncher.pinGameLoaderIfAbsent(second);

        assertThat(LaminateQuiltLauncher.pinnedGameLoader())
                .isSameAs(first)
                .isNotSameAs(second);
    }

    @Test
    @DisplayName("repeated calls with the same loader remain a no-op after the first pin")
    void repeatedCallsWithSameLoaderAreIdempotent() {
        ClassLoader loader = newDistinctClassLoader();

        LaminateQuiltLauncher.pinGameLoaderIfAbsent(loader);
        LaminateQuiltLauncher.pinGameLoaderIfAbsent(loader);
        LaminateQuiltLauncher.pinGameLoaderIfAbsent(loader);

        assertThat(LaminateQuiltLauncher.pinnedGameLoader()).isSameAs(loader);
    }

    /**
     * A null call before the real pin must not itself count as "already pinned" - a subsequent,
     * real loader still gets recorded.
     */
    @Test
    @DisplayName("a null loader is silently ignored and never pinned")
    void nullLoaderIsIgnored() {
        LaminateQuiltLauncher.pinGameLoaderIfAbsent(null);

        assertThat(LaminateQuiltLauncher.pinnedGameLoader()).isNull();

        ClassLoader real = newDistinctClassLoader();
        LaminateQuiltLauncher.pinGameLoaderIfAbsent(real);

        assertThat(LaminateQuiltLauncher.pinnedGameLoader()).isSameAs(real);
    }

    /**
     * Whichever loader wins the race, exactly one of the candidates must be recorded, and it must
     * stay that way - checked again after the fact to rule out a lost/overwritten update.
     */
    @Test
    @DisplayName("first-write-wins holds under concurrent racing callers")
    void firstWriteWinsUnderConcurrency() throws Exception {
        int threadCount = 32;
        ClassLoader[] loaders = new ClassLoader[threadCount];
        for (int i = 0; i < threadCount; i++) {
            loaders[i] = newDistinctClassLoader();
        }

        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            ClassLoader loader = loaders[i];
            threads[i] = new Thread(() -> LaminateQuiltLauncher.pinGameLoaderIfAbsent(loader));
        }
        for (Thread t : threads) {
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }

        ClassLoader pinned = LaminateQuiltLauncher.pinnedGameLoader();
        assertThat(pinned).isNotNull();
        assertThat(pinned).isIn((Object[]) loaders);
        assertThat(LaminateQuiltLauncher.pinnedGameLoader()).isSameAs(pinned);
    }

    private static ClassLoader newDistinctClassLoader() {
        return new URLClassLoader(new URL[0], null);
    }
}
