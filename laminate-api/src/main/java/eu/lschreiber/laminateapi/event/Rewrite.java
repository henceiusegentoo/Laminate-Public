package eu.lschreiber.laminateapi.event;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Describes a JVM method signature using {@code Class<?>} objects.
 *
 * <p>Used as the value of {@link InjectEvent#methodRewrite()} to unambiguously
 * identify an overloaded NMS target method by its parameter and return types.</p>
 *
 * <pre>{@code
 * // void tick()
 * @Rewrite(method = "tick")
 *
 * // void tick(BooleanSupplier)
 * @Rewrite(method = "tick", args = BooleanSupplier.class)
 *
 * // boolean hasChunkAt(BlockPos)
 * @Rewrite(method = "hasChunkAt", returns = boolean.class, args = BlockPos.class)
 *
 * // ServerChunkCache getChunkSource()
 * @Rewrite(method = "getChunkSource", returns = ServerChunkCache.class)
 * }</pre>
 *
 * @see InjectEvent#methodRewrite()
 */
@Target({})
@Retention(RetentionPolicy.CLASS)
public @interface Rewrite {

    /** Method name. */
    String method();

    /** Return type ({@code void.class} by default). */
    Class<?> returns() default void.class;

    /** Parameter types in declaration order. */
    Class<?>[] args() default {};
}

