package eu.lschreiber.laminateapi.event;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/** Fires Bukkit events from injected NMS code without static Bukkit references. */
public final class LaminateEventBridge {

    private LaminateEventBridge() {
    }

    @SuppressWarnings("rawtypes")
    private static final Constructor UNRESOLVED_SENTINEL;

    static {
        try {
            UNRESOLVED_SENTINEL = Object.class.getDeclaredConstructor();
        } catch (final NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @SuppressWarnings("rawtypes")
    private static final ConcurrentHashMap<String, Constructor> CACHE = new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<ClassLoader, BukkitHandle> BUKKIT_HANDLES = new ConcurrentHashMap<>();

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

    private record BukkitHandle(Object pluginManager, Method getPlugins, Method callEvent, Logger logger) {
    }

    public static void fire(final String eventClassName,
                            final Object target,
                            final Object... extraArgs) {
        constructAndFire(eventClassName, target, extraArgs);
    }

    public static Object fireAndReturn(final String eventClassName,
                                      final Object target,
                                      final Object... extraArgs) {
        return constructAndFire(eventClassName, target, extraArgs);
    }

    private static Object constructAndFire(final String eventClassName,
                                          final Object target,
                                          final Object[] extraArgs) {
        final ClassLoader loader = target.getClass().getClassLoader();
        final BukkitHandle bukkit = resolveBukkitHandle(loader);
        if (bukkit == null) {
            return null;
        }

        final Constructor<?> ctor = resolveConstructor(eventClassName, target, extraArgs, bukkit);
        if (ctor == UNRESOLVED_SENTINEL) {
            return null;
        }
        try {
            final Object[] ctorArgs = prependTarget(target, extraArgs);
            final Object event = ctor.newInstance(ctorArgs);
            bukkit.callEvent().invoke(bukkit.pluginManager(), event);
            return event;
        } catch (final Exception e) {
            bukkit.logger().warning("[LaminateEventBridge] Failed to fire event " + eventClassName + ": " + e);
            return null;
        }
    }

    private static BukkitHandle resolveBukkitHandle(final ClassLoader loader) {
        final BukkitHandle cached = BUKKIT_HANDLES.get(loader);
        if (cached != null) {
            return cached;
        }
        try {
            final Class<?> bukkitClass = Class.forName("org.bukkit.Bukkit", true, loader);
            final Class<?> eventClass = Class.forName("org.bukkit.event.Event", true, loader);
            final Object pluginManager = bukkitClass.getMethod("getPluginManager").invoke(null);
            final Method getPlugins = pluginManager.getClass().getMethod("getPlugins");
            final Method callEvent = pluginManager.getClass().getMethod("callEvent", eventClass);
            final Logger logger = (Logger) bukkitClass.getMethod("getLogger").invoke(null);
            final BukkitHandle handle = new BukkitHandle(pluginManager, getPlugins, callEvent, logger);
            final BukkitHandle raced = BUKKIT_HANDLES.putIfAbsent(loader, handle);
            return raced != null ? raced : handle;
        } catch (final ReflectiveOperationException e) {
            System.out.println("[ERROR] [LaminateEventBridge] Cannot resolve the Bukkit API via loader "
                    + loader + " — events will not be fired.");
            e.printStackTrace(System.out);
            return null;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Constructor<?> resolveConstructor(final String eventClassName,
                                                     final Object target,
                                                     final Object[] extraArgs,
                                                     final BukkitHandle bukkit) {
        final String cacheKey = buildCacheKey(eventClassName, target, extraArgs);
        final Constructor cached = CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        final Class<?> eventClass = loadEventClass(eventClassName, bukkit);
        if (eventClass == null) {
            CACHE.put(cacheKey, UNRESOLVED_SENTINEL);
            return UNRESOLVED_SENTINEL;
        }

        final Class<?>[] paramTypes = deriveParamTypes(target, extraArgs);
        final Constructor<?> ctor = findCompatibleConstructor(eventClass, paramTypes);
        if (ctor == null) {
            bukkit.logger().warning(
                    "[LaminateEventBridge] No matching constructor in " + eventClassName
                            + " for parameter types: " + describeTypes(paramTypes));
            CACHE.put(cacheKey, UNRESOLVED_SENTINEL);
            return UNRESOLVED_SENTINEL;
        }

        ctor.setAccessible(true);
        CACHE.putIfAbsent(cacheKey, ctor);
        return CACHE.get(cacheKey);
    }

    private static Class<?> loadEventClass(final String eventClassName, final BukkitHandle bukkit) {
        final Object[] plugins;
        try {
            plugins = (Object[]) bukkit.getPlugins().invoke(bukkit.pluginManager());
        } catch (final ReflectiveOperationException e) {
            bukkit.logger().warning(
                    "[LaminateEventBridge] Failed to enumerate plugins while resolving '"
                            + eventClassName + "': " + e);
            return null;
        }
        for (final Object plugin : plugins) {
            try {
                return plugin.getClass().getClassLoader().loadClass(eventClassName);
            } catch (final ClassNotFoundException ignored) {
            }
        }
        bukkit.logger().warning(
                "[LaminateEventBridge] Cannot find event class '" + eventClassName
                        + "' in any registered plugin classloader. Is the plugin loaded?");
        return null;
    }

    private static Constructor<?> findCompatibleConstructor(final Class<?> eventClass,
                                                            final Class<?>[] argTypes) {
        for (final Constructor<?> ctor : eventClass.getDeclaredConstructors()) {
            if (isCompatible(ctor.getParameterTypes(), argTypes)) {
                return ctor;
            }
        }
        return null;
    }

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

    private static Class<?> unwrap(final Class<?> type) {
        return BOXED_TO_PRIMITIVE.getOrDefault(type, type);
    }

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
