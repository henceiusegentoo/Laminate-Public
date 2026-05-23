package eu.lschreiber.laminateapi.accessor;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a mixin method for which the Laminate annotation processor should
 * generate a typed accessor interface.
 *
 * <p>For each annotated method the processor emits a
 * {@code {TargetSimpleName}Accessor} interface in a sibling {@code .accessor}
 * package, with a static {@code of(Object)} factory and a single interface
 * method whose name is taken from {@link #name()} (or the method's own name
 * when omitted).</p>
 *
 * <pre>{@code
 * @Mixin(MinecraftServer.class)
 * public class ServerMixin {
 *
 *     @GenerateAccessor(name = "write")
 *     public void hybridPlugin$write() { ... }
 *
 *     @GenerateAccessor
 *     public void hybridPlugin$log() { ... }
 * }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface GenerateAccessor {

    /**
     * Name to use for the generated accessor interface method.
     * When omitted (or set to {@code ""}), the annotated method's own name is
     * used unchanged.
     */
    String name() default "";
}
