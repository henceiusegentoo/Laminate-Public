package eu.lschreiber.laminateapi.event;

import eu.lschreiber.laminateapi.internal.JvmDescriptors;

import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.MirroredTypesException;
import javax.lang.model.type.TypeMirror;

/**
 * Converts a {@link Rewrite} annotation into a JVM method descriptor string
 * suitable for use in Mixin's {@code @Inject(method = ...)} attribute.
 *
 * <p>Works in both runtime and annotation-processing contexts: the
 * {@link MirroredTypeException}/{@link MirroredTypesException} that the JVM
 * throws when class literals are read during compilation are handled
 * transparently.</p>
 *
 * <h3>Examples of produced descriptors</h3>
 * <pre>
 * {@code @Rewrite(method = "tick")}
 *     → "tick()V"
 *
 * {@code @Rewrite(method = "tick", args = BooleanSupplier.class)}
 *     → "tick(Ljava/util/function/BooleanSupplier;)V"
 *
 * {@code @Rewrite(method = "hasChunkAt", returns = boolean.class, args = BlockPos.class)}
 *     → "hasChunkAt(Lnet/minecraft/core/BlockPos;)Z"
 *
 * {@code @Rewrite(method = "getChunkSource", returns = ServerChunkCache.class)}
 *     → "getChunkSource()Lnet/minecraft/server/level/ServerChunkCache;"
 * </pre>
 *
 * @see Rewrite
 * @see InjectEventProcessor
 */
final class RewriteProcessor {

    private RewriteProcessor() {}

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Converts a {@link Rewrite} annotation into the full JVM method descriptor.
     *
     * @param rewrite the annotation to convert; must have a non-blank
     *                {@link Rewrite#method()} value
     * @return the JVM descriptor, e.g. {@code "tick(Ljava/util/function/BooleanSupplier;)V"}
     * @throws IllegalArgumentException if {@link Rewrite#method()} is blank or null
     */
    static String toDescriptor(final Rewrite rewrite) {
        final String methodName = rewrite.method();
        if (methodName == null || methodName.isBlank()) {
            throw new IllegalArgumentException("@Rewrite method() must not be blank");
        }
        final String returnDesc = resolveReturnDescriptor(rewrite);
        final String argDescs   = resolveArgDescriptors(rewrite);
        return methodName + "(" + argDescs + ")" + returnDesc;
    }

    // ── Type resolution ──────────────────────────────────────────────────────

    /**
     * Resolves the return-type JVM descriptor, handling both the runtime case
     * (an actual {@code Class<?>} object is available) and the annotation-
     * processing case (only a {@link TypeMirror} is available via
     * {@link MirroredTypeException}).
     */
    private static String resolveReturnDescriptor(final Rewrite rewrite) {
        try {
            return JvmDescriptors.fromClass(rewrite.returns());
        } catch (final MirroredTypeException mte) {
            return JvmDescriptors.fromTypeMirror(mte.getTypeMirror());
        }
    }

    /**
     * Resolves all argument-type JVM descriptors, handling both the runtime
     * case and the annotation-processing case (via {@link MirroredTypesException}).
     */
    private static String resolveArgDescriptors(final Rewrite rewrite) {
        final StringBuilder sb = new StringBuilder();
        try {
            for (final Class<?> arg : rewrite.args()) {
                sb.append(JvmDescriptors.fromClass(arg));
            }
        } catch (final MirroredTypesException mte) {
            for (final TypeMirror tm : mte.getTypeMirrors()) {
                sb.append(JvmDescriptors.fromTypeMirror(tm));
            }
        }
        return sb.toString();
    }
}

