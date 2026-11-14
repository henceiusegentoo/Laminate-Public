package eu.lschreiber.laminateapi.internal;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.List;
import java.util.Map;

/** Resolves the single target declared on a {@code @Mixin} annotation. */
public final class MixinTargetResolver {

    private static final String MIXIN_FQN = "org.spongepowered.asm.mixin.Mixin";

    private MixinTargetResolver() {
    }

    /** Resolves the mixin target for a class. */
    public static MixinTarget resolve(final TypeElement mixinClass,
                                      final ProcessingEnvironment env,
                                      final String caller) {
        final Messager messager = env.getMessager();

        for (final AnnotationMirror mirror : mixinClass.getAnnotationMirrors()) {
            final TypeElement annotationType =
                    (TypeElement) mirror.getAnnotationType().asElement();

            if (!annotationType.getQualifiedName().contentEquals(MIXIN_FQN)) {
                continue;
            }

            final Map<? extends ExecutableElement, ? extends AnnotationValue> values =
                    mirror.getElementValues();

            for (final Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : values.entrySet()) {
                final String attrName = entry.getKey().getSimpleName().toString();

                if ("value".equals(attrName)) {
                    return resolveFromValueAttr(entry.getValue(), mixinClass, env, messager, caller);
                }
                if ("targets".equals(attrName)) {
                    return resolveFromTargetsAttr(entry.getValue(), mixinClass, messager, caller);
                }
            }
        }

        return null;
    }

    private static MixinTarget resolveFromValueAttr(final AnnotationValue annotationValue,
                                                    final TypeElement mixinClass,
                                                    final ProcessingEnvironment env,
                                                    final Messager messager,
                                                    final String caller) {
        final List<?> list = asSingleAnnotationValueList(annotationValue);
        if (list == null) {
            reportSingleTargetError(mixinClass, messager, caller);
            return null;
        }

        final Object first = ((AnnotationValue) list.getFirst()).getValue();
        if (first instanceof TypeMirror tm) {
            final Element element = env.getTypeUtils().asElement(tm);
            if (element instanceof TypeElement te) {
                return new MixinTarget(
                        te.getSimpleName().toString(),
                        te.getQualifiedName().toString());
            }
        }
        return null;
    }

    private static MixinTarget resolveFromTargetsAttr(final AnnotationValue annotationValue,
                                                      final TypeElement mixinClass,
                                                      final Messager messager,
                                                      final String caller) {
        final List<?> list = asSingleAnnotationValueList(annotationValue);
        if (list == null) {
            reportSingleTargetError(mixinClass, messager, caller);
            return null;
        }

        final String fqn = ((AnnotationValue) list.getFirst()).getValue()
                .toString()
                .replace('/', '.');
        final int lastDot = fqn.lastIndexOf('.');
        final String simpleName = lastDot < 0 ? fqn : fqn.substring(lastDot + 1);
        return new MixinTarget(simpleName, fqn);
    }

    private static List<?> asSingleAnnotationValueList(final AnnotationValue annotationValue) {
        final Object raw = annotationValue.getValue();
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        if (list.size() > 1) {
            return null;
        }
        return list;
    }

    private static void reportSingleTargetError(final TypeElement mixinClass,
                                                final Messager messager,
                                                final String caller) {
        messager.printMessage(Diagnostic.Kind.ERROR,
                "@Mixin has multiple targets; " + caller + " supports only one",
                mixinClass);
    }

    /**
     * Holds the resolved target class identity.
     *
     * @param simpleName target simple name
     * @param fqn target fully-qualified name
     */
    public record MixinTarget(String simpleName, String fqn) {
    }
}
