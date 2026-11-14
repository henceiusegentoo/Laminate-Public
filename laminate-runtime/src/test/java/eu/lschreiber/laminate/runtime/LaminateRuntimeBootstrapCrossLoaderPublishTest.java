package eu.lschreiber.laminate.runtime;

import eu.lschreiber.laminate.bridge.LaminateBridge;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for {@link LaminateRuntimeBootstrap#publishToBundlerNamespaceBridge} — the
 * cross-classloader-identity fix added by phase 4 task 4 after live testing found
 * {@code LaminateBridge} silently held two independent copies of its own state.
 *
 * <p>{@link LaminateRuntimeBootstrapTest} and {@link LaminateRuntimeBootstrapEmptyPluginsTest}
 * both run in a single-classloader JVM, so in both of them
 * {@code publishToBundlerNamespaceBridge}'s {@code bridgeClass == LaminateBridge.class} check is
 * always {@code true} and the method returns immediately — neither ever exercises the actual
 * reflective republish call this fix depends on, only the redundant-no-op and
 * exception-caught-and-swallowed paths. That gap is exactly the kind of thing that let the
 * original bug ship silently for an entire phase (see
 * {@code docs/plans/phase-4-core-mixins.md}'s "A second cross-classloader identity bug" addendum).
 *
 * <p>This test closes it directly: it builds a second, independent {@link URLClassLoader} over the
 * exact same classpath entry {@code LaminateBridge.class} itself was loaded from, parented to the
 * platform classloader so it does <em>not</em> delegate to (and cannot resolve types via) whatever
 * loaded this test class — the same non-delegating relationship {@code bundlerLoader} has with the
 * JVM's application classloader in production. Resolving {@code LaminateBridge} through that second
 * loader genuinely produces a distinct {@code Class} object with its own, independently-empty
 * static state — confirmed below before trusting anything else — then drives the real, private
 * {@code publishToBundlerNamespaceBridge} method (via reflection, matching this suite's established
 * pattern) and asserts the *other* copy's state actually changed.
 */
@DisplayName("LaminateRuntimeBootstrap cross-classloader LaminateBridge publish")
class LaminateRuntimeBootstrapCrossLoaderPublishTest {

    private URLClassLoader isolatedLoader;

    @AfterEach
    void resetStaticStateAndCloseLoader() throws Exception {
        setStaticField("excludedBundlerLoader", null);
        Field bridgeResultField = LaminateBridge.class.getDeclaredField("RESULT");
        bridgeResultField.setAccessible(true);
        ((AtomicReference<?>) bridgeResultField.get(null)).set(null);

        if (isolatedLoader != null) {
            isolatedLoader.close();
            isolatedLoader = null;
        }
    }

    /**
     * {@code excludedBundlerLoader} is set to the isolated loader because that is the exact field
     * {@code publishToBundlerNamespaceBridge} reflectively resolves {@code LaminateBridge}
     * through in production. Once the reflective publish call runs, the isolated copy - standing
     * in for what woven core-mixin code actually reads in production - must reflect the published
     * result, while this test's own {@code LaminateBridge} copy must remain untouched:
     * {@code publishToBundlerNamespaceBridge} only publishes into the <em>other</em> namespace,
     * and the ordinary {@code recordResult} call (exercised elsewhere) covers this one.
     */
    @Test
    @DisplayName("republishes into a genuinely distinct LaminateBridge Class object, not just this test's own copy")
    void republishesIntoADistinctBridgeCopy() throws Exception {
        CodeSource codeSource = LaminateBridge.class.getProtectionDomain().getCodeSource();
        assertThat(codeSource).as("LaminateBridge must be loaded from a real classpath entry for this test to mean anything").isNotNull();
        URL classpathEntry = codeSource.getLocation();

        isolatedLoader = new URLClassLoader(new URL[] {classpathEntry}, ClassLoader.getPlatformClassLoader());

        Class<?> isolatedBridgeClass = Class.forName("eu.lschreiber.laminate.bridge.LaminateBridge", true, isolatedLoader);

        assertThat(isolatedBridgeClass).isNotSameAs(LaminateBridge.class);
        assertThat(isolatedBridgeClass.getClassLoader()).isSameAs(isolatedLoader);
        Method isolatedIsActive = isolatedBridgeClass.getMethod("isActive");
        assertThat((Boolean) isolatedIsActive.invoke(null)).as("isolated copy must start unpublished").isFalse();

        assertThat(LaminateBridge.isActive()).isFalse();

        setStaticField("excludedBundlerLoader", isolatedLoader);

        Set<String> loaded = Set.of("hybrid-plugin-one");
        Set<String> failed = Set.of("broken-plugin");

        Method publish = LaminateRuntimeBootstrap.class.getDeclaredMethod(
                "publishToBundlerNamespaceBridge", Set.class, Set.class);
        publish.setAccessible(true);
        publish.invoke(null, loaded, failed);

        assertThat((Boolean) isolatedIsActive.invoke(null)).isTrue();
        Method isolatedLoadedNames = isolatedBridgeClass.getMethod("loadedHybridPluginNames");
        Method isolatedFailedNames = isolatedBridgeClass.getMethod("failedHybridPluginNames");
        @SuppressWarnings("unchecked")
        Set<String> isolatedLoaded = (Set<String>) isolatedLoadedNames.invoke(null);
        @SuppressWarnings("unchecked")
        Set<String> isolatedFailed = (Set<String>) isolatedFailedNames.invoke(null);
        assertThat(isolatedLoaded).containsExactly("hybrid-plugin-one");
        assertThat(isolatedFailed).containsExactly("broken-plugin");

        assertThat(LaminateBridge.isActive()).isFalse();
    }

    /**
     * Setting {@code excludedBundlerLoader} to this test's own classloader reproduces the
     * {@code bridgeClass == LaminateBridge.class} branch inside
     * {@code publishToBundlerNamespaceBridge} (a loader that delegates to, or simply is, the
     * current classloader resolves back to the same {@code Class} object), confirming the
     * redundant-copy short-circuit neither throws nor double-publishes. {@code recordResult} is
     * never called directly here - only the skipped, redundant republish attempt - so this test's
     * own {@code LaminateBridge} copy must still be unpublished afterward.
     */
    @Test
    @DisplayName("is a no-op when excludedBundlerLoader resolves back to this test's own LaminateBridge copy")
    void skipsRedundantRepublishToTheSameCopy() throws Exception {
        setStaticField("excludedBundlerLoader", LaminateBridge.class.getClassLoader());

        Method publish = LaminateRuntimeBootstrap.class.getDeclaredMethod(
                "publishToBundlerNamespaceBridge", Set.class, Set.class);
        publish.setAccessible(true);
        publish.invoke(null, Set.of("x"), Set.of("y"));

        assertThat(LaminateBridge.isActive()).isFalse();
    }

    private static void setStaticField(String name, Object value) throws Exception {
        Field field = LaminateRuntimeBootstrap.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }
}
