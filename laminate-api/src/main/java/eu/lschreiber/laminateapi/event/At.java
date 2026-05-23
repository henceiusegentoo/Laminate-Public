package eu.lschreiber.laminateapi.event;

/**
 * Injection point within the target method body.
 *
 * <p>Maps directly to SpongePowered Mixin's {@code @At} value.</p>
 */
public enum At {
    /** Before the first instruction. */
    HEAD,
    /** Just before every {@code RETURN}. */
    TAIL,
    /** Immediately before each {@code RETURN} opcode. */
    RETURN,
    /** Before a specific method call; requires {@link InjectEvent#target()}. */
    INVOKE
}
