package eu.lschreiber.laminateapi.internal;

import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

/** Converts Java types to JVM descriptors. */
public final class JvmDescriptors {

    private JvmDescriptors() {
    }

    /** Converts a runtime {@link Class} to a JVM descriptor. */
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

    /** Converts a compile-time {@link TypeMirror} to a JVM descriptor. */
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
