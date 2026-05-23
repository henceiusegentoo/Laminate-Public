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

/**
 * Resolves the target class declared in a {@code @Mixin} annotation.
 *
 * <p>Both {@code @Mixin(SomeClass.class)} (the {@code value} attribute) and
 * {@code @Mixin(targets = "com.example.SomeClass")} (the {@code targets}
 * attribute) are supported. Only single-target mixins are supported; a
 * compile-time error is emitted when multiple targets are declared.</p>
 *
 * <p>This utility is shared between {@code GenerateAccessorProcessor} and
 * {@code InjectEventProcessor} to avoid code duplication.</p>
 */
public final class MixinTargetResolver {

    private static final String MIXIN_FQN = "org.spongepowered.asm.mixin.Mixin";

    private MixinTargetResolver() {
    }

    /**
     * Resolves the single mixin target from the {@code @Mixin} annotation on
     * the given type element.
     *
     * @param mixinClass the mixin class element annotated with {@code @Mixin}
     * @param env        the processing environment for type utilities
     * @param caller     a short label for error messages (e.g. {@code "@GenerateAccessor"})
     * @return the resolved target, or {@code null} if resolution failed
     */
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

            for (final var entry : values.entrySet()) {
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

    /**
     * Extracts the target from the {@code value} attribute ({@code Class<?>[]}).
     */
    private static MixinTarget resolveFromValueAttr(final AnnotationValue annotationValue,
                                                    final TypeElement mixinClass,
                                                    final ProcessingEnvironment env,
                                                    final Messager messager,
                                                    final String caller) {
        final Object raw = annotationValue.getValue();
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return null;
        }

        if (list.size() > 1) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "@Mixin has multiple targets; " + caller + " supports only one",
                    mixinClass);
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

    /**
     * Extracts the target from the {@code targets} attribute ({@code String[]}).
     */
    private static MixinTarget resolveFromTargetsAttr(final AnnotationValue annotationValue,
                                                      final TypeElement mixinClass,
                                                      final Messager messager,
                                                      final String caller) {
        final Object raw = annotationValue.getValue();
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return null;
        }

        if (list.size() > 1) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "@Mixin has multiple targets; " + caller + " supports only one",
                    mixinClass);
            return null;
        }

        final String fqn = ((AnnotationValue) list.getFirst()).getValue()
                .toString()
                .replace('/', '.');
        final int lastDot = fqn.lastIndexOf('.');
        final String simpleName = lastDot < 0 ? fqn : fqn.substring(lastDot + 1);
        return new MixinTarget(simpleName, fqn);
    }

    /**
     * Holds the resolved target class identity.
     *
     * @param simpleName the unqualified class name (e.g. {@code "ServerLevel"})
     * @param fqn        the fully-qualified class name (e.g. {@code "net.minecraft.server.level.ServerLevel"})
     */
    public record MixinTarget(String simpleName, String fqn) {
    }
}
