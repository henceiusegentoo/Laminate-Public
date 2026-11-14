package eu.lschreiber.laminateapi.event;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a mixin method that should fire a Bukkit event. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
public @interface InjectEvent {

    /** String shorthand for the target NMS method. */
    String method() default "";

    /** Type-safe descriptor for the target NMS method. */
    Rewrite methodRewrite() default @Rewrite(method = "");

    /** Injection point within the target method. */
    At at();

    /** Bukkit event class to instantiate and fire. */
    Class<?> event();

    /** Whether the generated injection may cancel the target method. */
    boolean cancellable() default false;

    /** JVM-format call-site descriptor for {@link At#INVOKE} injections. */
    String target() default "";
}
