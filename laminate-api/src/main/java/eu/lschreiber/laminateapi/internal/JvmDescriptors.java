package eu.lschreiber.laminateapi.internal;

import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

/**
 * Converts Java types to their JVM type descriptor strings.
 *
 * <p>This utility supports both runtime {@link Class} objects (used when
 * annotation values are directly accessible) and compile-time
 * {@link TypeMirror} objects (used when the annotation processor catches
 * {@link javax.lang.model.type.MirroredTypeException}).</p>
 *
 * <p>Examples of JVM descriptors:</p>
 * <ul>
 *   <li>{@code void} → {@code "V"}</li>
 *   <li>{@code int} → {@code "I"}</li>
 *   <li>{@code String} → {@code "Ljava/lang/String;"}</li>
 *   <li>{@code int[]} → {@code "[I"}</li>
 * </ul>
 *
 * <p>This class is internal to {@code laminate-api} and not part of the
 * public API.</p>
 */
public final class JvmDescriptors {

    private JvmDescriptors() {
    }

    /**
     * Converts a runtime {@link Class} to its JVM type descriptor.
     *
     * @param type the class to convert (must not be {@code null})
     * @return the JVM descriptor string
     */
    public static String fromClass(final Class<?> type) {
        if (type == void.class)    return "V";
        if (type == boolean.class) return "Z";
        if (type == byte.class)    return "B";
        if (type == char.class)    return "C";
        if (type == short.class)   return "S";
        if (type == int.class)     return "I";
        if (type == long.class)    return "J";
        if (type == float.class)   return "F";
        if (type == double.class)  return "D";
        if (type.isArray()) {
            return "[" + fromClass(type.getComponentType());
        }
        return "L" + type.getName().replace('.', '/') + ";";
    }

    /**
     * Converts a compile-time {@link TypeMirror} to its JVM type descriptor.
     *
     * @param mirror the type mirror to convert (must not be {@code null})
     * @return the JVM descriptor string
     * @throws IllegalArgumentException if the type kind is unsupported
     */
    public static String fromTypeMirror(final TypeMirror mirror) {
        return switch (mirror.getKind()) {
            case VOID    -> "V";
            case BOOLEAN -> "Z";
            case BYTE    -> "B";
            case CHAR    -> "C";
            case SHORT   -> "S";
            case INT     -> "I";
            case LONG    -> "J";
            case FLOAT   -> "F";
            case DOUBLE  -> "D";
            case ARRAY   -> "[" + fromTypeMirror(((ArrayType) mirror).getComponentType());
            case DECLARED -> {
                final TypeElement element =
                        (TypeElement) ((DeclaredType) mirror).asElement();
                yield "L" + binaryName(element).replace('.', '/') + ";";
            }
            default -> throw new IllegalArgumentException("Unsupported type: " + mirror);
        };
    }

    /**
     * Derives the JVM binary name for a {@link TypeElement}, correctly using
     * {@code $} as the separator for nested/inner classes.
     *
     * <p>Example: {@code Map.Entry} → {@code "java.util.Map$Entry"}.</p>
     */
    private static String binaryName(final TypeElement element) {
        if (element.getEnclosingElement() instanceof final TypeElement enclosing) {
            return binaryName(enclosing) + "$" + element.getSimpleName();
        }
        final String pkg =
                ((PackageElement) element.getEnclosingElement()).getQualifiedName().toString();
        final String simpleName = element.getSimpleName().toString();
        return pkg.isEmpty() ? simpleName : pkg + "." + simpleName;
    }
}
