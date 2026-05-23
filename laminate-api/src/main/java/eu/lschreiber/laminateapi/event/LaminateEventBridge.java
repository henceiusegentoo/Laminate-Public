package eu.lschreiber.laminateapi.event;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime bridge used by generated {@code @InjectEvent} companion Mixin classes
 * to fire Bukkit events without holding a direct bytecode reference to the
 * event class.
 *
 * <h2>Why this exists</h2>
 * <p>Generated mixin companion classes are injected into NMS classes (e.g.
 * {@code ServerLevel}). Those NMS classes are loaded by the server's
 * {@code AppClassLoader}, which cannot see classes defined in plugin JARs
 * (loaded by child {@code PluginClassLoader} instances). A direct
 * {@code new EventClass(…)} in the injected bytecode would cause a
 * {@link NoClassDefFoundError} at runtime.</p>
 *
 * <p>{@code LaminateEventBridge} is part of {@code laminate-api}, which is an
 * {@code implementation} dependency of {@code paper-server} and therefore
 * always on the {@code AppClassLoader}'s classpath. At the first call for a
 * given event class name, this class iterates the registered {@link Plugin}
 * instances to find a class loader that can load the event class, then caches
 * the resolved {@link Constructor} for subsequent calls.</p>
 *
 * <h2>Thread safety</h2>
 * <p>The constructor cache uses {@link ConcurrentHashMap} with an
 * absent-sentinel to avoid repeated failed lookups. Cache population is
 * idempotent; the only effect of two threads racing to populate the same entry
 * is a redundant {@code loadClass} call.</p>
 */
public final class LaminateEventBridge {

    private LaminateEventBridge() {
    }

    /**
     * Sentinel stored in the cache when a class cannot be resolved, so we do
     * not retry on every tick.
     */
    @SuppressWarnings("rawtypes")
    private static final Constructor UNRESOLVED_SENTINEL;

    static {
        try {
            UNRESOLVED_SENTINEL = Object.class.getDeclaredConstructor();
        } catch (final NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Cache: (event FQN + arg-type descriptor) → resolved constructor (or sentinel). */
    @SuppressWarnings("rawtypes")
    private static final ConcurrentHashMap<String, Constructor> CACHE = new ConcurrentHashMap<>();

    /** Boxed-to-primitive mapping for constructor compatibility checks. */
    private static final Map<Class<?>, Class<?>> BOXED_TO_PRIMITIVE = Map.of(
            Integer.class,   int.class,
            Long.class,      long.class,
            Double.class,    double.class,
            Float.class,     float.class,
            Boolean.class,   boolean.class,
            Byte.class,      byte.class,
            Short.class,     short.class,
            Character.class, char.class
    );

    /**
     * Resolves, instantiates and fires a Bukkit event, then discards it.
     *
     * @param eventClassName fully-qualified event class name
     * @param target         the NMS object that is the event's primary subject
     *                       (first constructor argument)
     * @param extraArgs      additional constructor arguments after {@code target}
     * @see #fireAndReturn(String, Object, Object...)
     */
    public static void fire(final String eventClassName,
                            final Object target,
                            final Object... extraArgs) {
        constructAndFire(eventClassName, target, extraArgs);
    }

    /**
     * Resolves, instantiates and fires a Bukkit event, returning the fired
     * event so callers can inspect cancellation state.
     *
     * @param eventClassName fully-qualified event class name
     * @param target         the NMS object that is the event's primary subject
     * @param extraArgs      additional constructor arguments after {@code target}
     * @return the fired event, or {@code null} if resolution/construction failed
     */
    public static Event fireAndReturn(final String eventClassName,
                                      final Object target,
                                      final Object... extraArgs) {
        return constructAndFire(eventClassName, target, extraArgs);
    }

    // ── Core logic ──────────────────────────────────────────────────────────

    /**
     * Shared implementation: resolves the constructor, creates the event,
     * fires it, and returns it (or {@code null} on failure).
     */
    private static Event constructAndFire(final String eventClassName,
                                          final Object target,
                                          final Object[] extraArgs) {
        final Constructor<?> ctor = resolveConstructor(eventClassName, target, extraArgs);
        if (ctor == UNRESOLVED_SENTINEL) {
            return null;
        }
        try {
            final Object[] ctorArgs = prependTarget(target, extraArgs);
            final Event event = (Event) ctor.newInstance(ctorArgs);
            Bukkit.getPluginManager().callEvent(event);
            return event;
        } catch (final Exception e) {
            Bukkit.getLogger().warning(
                    "[LaminateEventBridge] Failed to fire event " + eventClassName + ": " + e);
            return null;
        }
    }

    // ── Constructor resolution ───────────────────────────────────────────────

    /**
     * Looks up the appropriate constructor for the given event class and
     * argument types, caching the result for subsequent calls.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Constructor<?> resolveConstructor(final String eventClassName,
                                                     final Object target,
                                                     final Object[] extraArgs) {
        final String cacheKey = buildCacheKey(eventClassName, target, extraArgs);
        final Constructor cached = CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        final Class<?> eventClass = loadEventClass(eventClassName);
        if (eventClass == null) {
            CACHE.put(cacheKey, UNRESOLVED_SENTINEL);
            return UNRESOLVED_SENTINEL;
        }

        final Class<?>[] paramTypes = deriveParamTypes(target, extraArgs);
        final Constructor<?> ctor = findCompatibleConstructor(eventClass, paramTypes);
        if (ctor == null) {
            Bukkit.getLogger().warning(
                    "[LaminateEventBridge] No matching constructor in " + eventClassName
                            + " for parameter types: " + describeTypes(paramTypes));
            CACHE.put(cacheKey, UNRESOLVED_SENTINEL);
            return UNRESOLVED_SENTINEL;
        }

        ctor.setAccessible(true);
        CACHE.putIfAbsent(cacheKey, ctor);
        return CACHE.get(cacheKey);
    }

    /**
     * Attempts to load the event class from every registered plugin class loader.
     *
     * @return the loaded class, or {@code null} if not found
     */
    private static Class<?> loadEventClass(final String eventClassName) {
        for (final Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            try {
                return plugin.getClass().getClassLoader().loadClass(eventClassName);
            } catch (final ClassNotFoundException ignored) {
                // Try next plugin's class loader.
            }
        }
        Bukkit.getLogger().warning(
                "[LaminateEventBridge] Cannot find event class '" + eventClassName
                        + "' in any registered plugin classloader. Is the plugin loaded?");
        return null;
    }

    /**
     * Finds the first declared constructor whose parameter types are all
     * compatible with the given argument types.
     */
    private static Constructor<?> findCompatibleConstructor(final Class<?> eventClass,
                                                            final Class<?>[] argTypes) {
        for (final Constructor<?> ctor : eventClass.getDeclaredConstructors()) {
            if (isCompatible(ctor.getParameterTypes(), argTypes)) {
                return ctor;
            }
        }
        return null;
    }

    /**
     * Checks whether every argument type is assignable to the corresponding
     * parameter type, accounting for {@code null}, primitive↔boxed equivalence,
     * and standard assignability.
     */
    private static boolean isCompatible(final Class<?>[] paramTypes,
                                        final Class<?>[] argTypes) {
        if (paramTypes.length != argTypes.length) {
            return false;
        }
        for (int i = 0; i < paramTypes.length; i++) {
            if (!isTypeCompatible(paramTypes[i], argTypes[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks single-type compatibility: {@code null} matches any non-primitive,
     * direct assignability, or primitive↔boxed equivalence.
     */
    private static boolean isTypeCompatible(final Class<?> paramType,
                                            final Class<?> argType) {
        if (argType == null) {
            return !paramType.isPrimitive();
        }
        if (paramType.isAssignableFrom(argType)) {
            return true;
        }
        return unwrap(paramType).isAssignableFrom(unwrap(argType));
    }

    /**
     * Returns the primitive type for a boxed wrapper, or the type itself.
     */
    private static Class<?> unwrap(final Class<?> type) {
        return BOXED_TO_PRIMITIVE.getOrDefault(type, type);
    }

    // ── Argument utilities ──────────────────────────────────────────────────

    /**
     * Builds a composite cache key from the event class name and runtime
     * argument types.
     */
    private static String buildCacheKey(final String eventClassName,
                                        final Object target,
                                        final Object[] extraArgs) {
        final StringBuilder sb = new StringBuilder(eventClassName).append('|');
        sb.append(target.getClass().getName());
        for (final Object arg : extraArgs) {
            sb.append(',');
            sb.append(arg == null ? "null" : arg.getClass().getName());
        }
        return sb.toString();
    }

    /**
     * Derives the expected constructor parameter types from runtime arguments.
     */
    private static Class<?>[] deriveParamTypes(final Object target,
                                               final Object[] extraArgs) {
        final Class<?>[] paramTypes = new Class<?>[1 + extraArgs.length];
        paramTypes[0] = target.getClass();
        for (int i = 0; i < extraArgs.length; i++) {
            paramTypes[i + 1] = extraArgs[i] == null ? null : extraArgs[i].getClass();
        }
        return paramTypes;
    }

    private static Object[] prependTarget(final Object target, final Object[] extraArgs) {
        final Object[] all = new Object[1 + extraArgs.length];
        all[0] = target;
        System.arraycopy(extraArgs, 0, all, 1, extraArgs.length);
        return all;
    }

    private static String describeTypes(final Class<?>[] types) {
        final StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < types.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(types[i] == null ? "null" : types[i].getName());
        }
        return sb.append(']').toString();
    }
}
