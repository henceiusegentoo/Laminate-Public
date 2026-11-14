package eu.lschreiber.laminateapi.event;

import eu.lschreiber.laminateapi.internal.JvmDescriptors;

import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.MirroredTypesException;
import javax.lang.model.type.TypeMirror;

/** Converts {@link Rewrite} annotations into JVM descriptors. */
final class RewriteProcessor {

    private RewriteProcessor() {}

    /** Converts a {@link Rewrite} annotation into a JVM method descriptor. */
    static String toDescriptor(final Rewrite rewrite) {
        final String methodName = rewrite.method();
        if (methodName == null || methodName.isBlank()) {
            throw new IllegalArgumentException("@Rewrite method() must not be blank");
        }
        final String returnDesc = resolveReturnDescriptor(rewrite);
        final String argDescs   = resolveArgDescriptors(rewrite);
        return methodName + "(" + argDescs + ")" + returnDesc;
    }

    private static String resolveReturnDescriptor(final Rewrite rewrite) {
        try {
            return JvmDescriptors.fromClass(rewrite.returns());
        } catch (final MirroredTypeException mte) {
            return JvmDescriptors.fromTypeMirror(mte.getTypeMirror());
        }
    }

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
