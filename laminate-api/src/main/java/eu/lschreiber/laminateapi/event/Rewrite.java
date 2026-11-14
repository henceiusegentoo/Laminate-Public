package eu.lschreiber.laminateapi.event;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Describes a target method using Java types. */
@Target({})
@Retention(RetentionPolicy.CLASS)
public @interface Rewrite {

    /** Target method name. */
    String method();

    /** Return type. */
    Class<?> returns() default void.class;

    /** Parameter types in declaration order. */
    Class<?>[] args() default {};
}
