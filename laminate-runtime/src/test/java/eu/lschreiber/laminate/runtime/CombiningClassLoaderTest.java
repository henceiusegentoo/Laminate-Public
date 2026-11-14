package eu.lschreiber.laminate.runtime;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link CombiningClassLoader} - the piece that lets
 * {@link LaminateQuiltLauncher#getTargetClassLoader()} see classes registered by hybrid plugins,
 * not just this launcher's own defining loader. Exercised directly, without constructing a
 * {@link LaminateQuiltLauncher} (whose {@code QuiltLauncherBase} superclass has global side
 * effects - see {@code LaminateQuiltLauncherPinnedGameLoaderTest}'s own javadoc for why this
 * package's tests avoid that).
 *
 * <p>Single-candidate resolution ({@code loadClass} delegation) and plugin-loader resolution
 * ({@code getResourceAsStream} + {@code defineClass}, added after a live {@code ClassCastException}
 * proved plain {@code loadClass} delegation to a plugin's own {@code URLClassLoader} makes that
 * loader the defining loader for shared framework types too - see the class javadoc) need two
 * different kinds of fakes, since they exercise genuinely different {@link ClassLoader} methods.
 */
@DisplayName("CombiningClassLoader")
class CombiningClassLoaderTest {

    // ---- parent delegation (the actual ClassCastException fix) ----

    @Test
    @DisplayName("a class the parent already has is resolved through the parent, never reaching findClass")
    void parentIsConsultedBeforeAnyCandidate() throws Exception {
        // Regression test for the live ClassCastException: a shared framework type must resolve to
        // the exact Class object its own loader (the parent here) already uses, not whatever a
        // singleCandidate happens to answer with. A candidate that would throw if ever queried
        // proves findClass is never reached for a name the parent already resolves.
        ClassLoader parent = FixtureA.class.getClassLoader();
        LoadClassFake mustNotBeQueried = new LoadClassFake("should.never.be.asked");
        CombiningClassLoader combined = new CombiningClassLoader(
                parent, List.of(supplierOf(mustNotBeQueried)), List.of());

        Class<?> resolved = combined.loadClass(FixtureA.class.getName());

        assertThat(resolved).isSameAs(FixtureA.class);
        assertThat(mustNotBeQueried.requested).isEmpty();
    }

    // ---- single-candidate resolution (loadClass delegation) ----

    @Test
    @DisplayName("resolves a class from the first single candidate that has it")
    void resolvesFromFirstMatchingSingleCandidate() throws Exception {
        LoadClassFake first = new LoadClassFake("a.A");
        LoadClassFake second = new LoadClassFake("b.B");
        CombiningClassLoader combined = new CombiningClassLoader(
                null, List.of(supplierOf(first), supplierOf(second)), List.of());

        Class<?> resolved = combined.loadClass("a.A");

        assertThat(resolved).isSameAs(Object.class);
        assertThat(first.requested).containsExactly("a.A");
        assertThat(second.requested).isEmpty();
    }

    @Test
    @DisplayName("falls through to a later single candidate when an earlier one doesn't have the class")
    void fallsThroughSingleCandidates() throws Exception {
        LoadClassFake first = new LoadClassFake("a.A");
        LoadClassFake second = new LoadClassFake("b.B");
        CombiningClassLoader combined = new CombiningClassLoader(
                null, List.of(supplierOf(first), supplierOf(second)), List.of());

        Class<?> resolved = combined.loadClass("b.B");

        assertThat(resolved).isSameAs(Object.class);
        assertThat(first.requested).containsExactly("b.B");
        assertThat(second.requested).containsExactly("b.B");
    }

    @Test
    @DisplayName("a null single-candidate supplier is skipped without error")
    void skipsNullSingleCandidate() throws Exception {
        LoadClassFake real = new LoadClassFake("a.A");
        CombiningClassLoader combined = new CombiningClassLoader(
                null, List.of(() -> null, supplierOf(real)), List.of());

        Class<?> resolved = combined.loadClass("a.A");

        assertThat(resolved).isSameAs(Object.class);
    }

    @Test
    @DisplayName("the same single-candidate loader instance recurring later is not queried twice")
    void deduplicatesRepeatedSingleCandidate() throws Exception {
        LoadClassFake shared = new LoadClassFake("a.A");
        CombiningClassLoader combined = new CombiningClassLoader(
                null, List.of(supplierOf(shared), supplierOf(shared)), List.of());

        combined.loadClass("a.A");

        assertThat(shared.requested).containsExactly("a.A");
    }

    // ---- plugin-loader resolution (getResourceAsStream + defineClass) ----

    @Test
    @DisplayName("falls through from single candidates to a plugin loader, defining the class on itself")
    void fallsThroughToPluginLoaderAndDefinesLocally() throws Exception {
        LoadClassFake base = new LoadClassFake("a.A");
        ResourceServingFake plugin = ResourceServingFake.servingRealClass(FixtureA.class);
        CombiningClassLoader combined = new CombiningClassLoader(
                null, List.of(supplierOf(base)), List.of(plugin));

        Class<?> resolved = combined.loadClass(FixtureA.class.getName());

        assertThat(resolved.getName()).isEqualTo(FixtureA.class.getName());
        // The whole point of the fix: CombiningClassLoader itself must be the defining loader, not
        // the plugin's own URLClassLoader - otherwise this class's own references to shared types
        // resolve through the plugin loader's chain instead of this loader's trusted candidates,
        // which is exactly the ClassCastException this design fixes.
        assertThat(resolved.getClassLoader()).isSameAs(combined);
    }

    @Test
    @DisplayName("a plugin loader with no matching resource is skipped, trying the next one")
    void skipsPluginLoaderWithoutTheResource() throws Exception {
        ResourceServingFake empty = ResourceServingFake.empty();
        ResourceServingFake real = ResourceServingFake.servingRealClass(FixtureA.class);
        CombiningClassLoader combined = new CombiningClassLoader(null, List.of(), List.of(empty, real));

        Class<?> resolved = combined.loadClass(FixtureA.class.getName());

        assertThat(resolved.getName()).isEqualTo(FixtureA.class.getName());
    }

    @Test
    @DisplayName("throws ClassNotFoundException when neither single candidates nor plugin loaders have the class")
    void throwsWhenNoCandidateHasIt() {
        CombiningClassLoader combined = new CombiningClassLoader(
                null, List.of(supplierOf(new LoadClassFake("a.A"))), List.of(ResourceServingFake.empty()));

        assertThatThrownBy(() -> combined.loadClass("missing.Class"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    private static Supplier<ClassLoader> supplierOf(final ClassLoader loader) {
        return () -> loader;
    }

    static final class FixtureA {
    }

    private static final class LoadClassFake extends ClassLoader {
        private final String ownedName;
        private final List<String> requested = new ArrayList<>();

        LoadClassFake(final String ownedName) {
            super(null);
            this.ownedName = ownedName;
        }

        @Override
        public Class<?> loadClass(final String name) throws ClassNotFoundException {
            this.requested.add(name);
            if (name.equals(this.ownedName)) {
                return Object.class;
            }
            throw new ClassNotFoundException(name);
        }
    }

    private static final class ResourceServingFake extends ClassLoader {
        private final Map<String, byte[]> resources;

        private ResourceServingFake(final Map<String, byte[]> resources) {
            super(null);
            this.resources = resources;
        }

        static ResourceServingFake empty() {
            return new ResourceServingFake(Map.of());
        }

        static ResourceServingFake servingRealClass(final Class<?> fixtureClass) {
            final String resourcePath = fixtureClass.getName().replace('.', '/') + ".class";
            try (InputStream in = fixtureClass.getResourceAsStream("/" + resourcePath)) {
                if (in == null) {
                    throw new IllegalStateException("Could not locate fixture class bytes for " + fixtureClass);
                }
                final Map<String, byte[]> map = new HashMap<>();
                map.put(resourcePath, in.readAllBytes());
                return new ResourceServingFake(map);
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public InputStream getResourceAsStream(final String name) {
            final byte[] bytes = this.resources.get(name);
            return bytes == null ? null : new ByteArrayInputStream(bytes);
        }
    }
}
