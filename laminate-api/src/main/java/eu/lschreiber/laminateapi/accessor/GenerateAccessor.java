package eu.lschreiber.laminateapi.accessor;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a mixin method for generated accessor support. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface GenerateAccessor {

    /** Name to use for the generated accessor method. */
    String name() default "";
}
