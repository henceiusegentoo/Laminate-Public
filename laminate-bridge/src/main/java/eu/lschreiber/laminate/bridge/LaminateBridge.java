package eu.lschreiber.laminate.bridge;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cross-classloader state for hybrid-plugin bootstrap results.
 */
public final class LaminateBridge {

    private static final AtomicReference<Result> RESULT = new AtomicReference<>();

    private LaminateBridge() {
    }

    public static void recordResult(Set<String> loadedPluginNames, Set<String> failedPluginNames) {
        Objects.requireNonNull(loadedPluginNames, "loadedPluginNames");
        Objects.requireNonNull(failedPluginNames, "failedPluginNames");

        Set<String> loaded = copy(loadedPluginNames, false);
        Set<String> failed = copy(failedPluginNames, true);
        RESULT.set(new Result(loaded, failed));
    }

    public static Set<String> loadedHybridPluginNames() {
        return currentOrEmpty().loaded();
    }

    public static Set<String> failedHybridPluginNames() {
        return currentOrEmpty().failed();
    }

    public static boolean isActive() {
        return RESULT.get() != null;
    }

    private static Result currentOrEmpty() {
        Result result = RESULT.get();
        return result != null ? result : Result.EMPTY;
    }

    private static Set<String> copy(Set<String> source, boolean lowerCase) {
        Set<String> copy = new LinkedHashSet<>(source.size());
        for (String name : source) {
            Objects.requireNonNull(name, "plugin name");
            copy.add(lowerCase ? name.toLowerCase(Locale.ROOT) : name);
        }
        return Collections.unmodifiableSet(copy);
    }

    private record Result(Set<String> loaded, Set<String> failed) {
        private static final Result EMPTY = new Result(Set.of(), Set.of());
    }
}
