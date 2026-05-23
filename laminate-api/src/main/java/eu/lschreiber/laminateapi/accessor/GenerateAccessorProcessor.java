package eu.lschreiber.laminateapi.accessor;

import eu.lschreiber.laminateapi.internal.MixinTargetResolver;
import eu.lschreiber.laminateapi.internal.MixinTargetResolver.MixinTarget;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Annotation processor that generates typed accessor interfaces for
 * {@link GenerateAccessor}-annotated mixin methods.
 *
 * <h2>Generated artefacts</h2>
 * <p>For each {@code @Mixin} class containing at least one {@code @GenerateAccessor}
 * method, a single accessor interface is emitted in a sibling {@code .accessor}
 * package (not inside the mixin package itself, which SpongePowered Mixin
 * forbids loading directly).</p>
 *
 * <p>The interface provides:</p>
 * <ul>
 *   <li>One typed method per {@code @GenerateAccessor} method.</li>
 *   <li>A static {@code of(Object)} factory backed by
 *       {@link LaminateAccessors#create}.</li>
 * </ul>
 */
@SupportedAnnotationTypes("eu.lschreiber.laminateapi.accessor.GenerateAccessor")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class GenerateAccessorProcessor extends AbstractProcessor {

    @Override
    public boolean process(final Set<? extends TypeElement> annotations,
                           final RoundEnvironment roundEnv) {
        if (annotations.isEmpty()) {
            return false;
        }

        final Map<TypeElement, List<AccessorEntry>> entriesByMixin = new LinkedHashMap<>();
        final Map<TypeElement, MixinTarget> targetByMixin = new LinkedHashMap<>();

        collectAnnotatedMethods(roundEnv, entriesByMixin, targetByMixin);
        generateAccessorInterfaces(entriesByMixin, targetByMixin);

        return true;
    }

    // ── Collection ──────────────────────────────────────────────────────────

    /**
     * Iterates all {@code @GenerateAccessor}-annotated methods, validates them,
     * and groups them by their enclosing mixin class.
     */
    private void collectAnnotatedMethods(final RoundEnvironment roundEnv,
                                         final Map<TypeElement, List<AccessorEntry>> entriesByMixin,
                                         final Map<TypeElement, MixinTarget> targetByMixin) {

        for (final Element element : roundEnv.getElementsAnnotatedWith(GenerateAccessor.class)) {
            if (!(element instanceof ExecutableElement method)) {
                continue;
            }

            final TypeElement mixinClass = (TypeElement) method.getEnclosingElement();
            final GenerateAccessor annotation = method.getAnnotation(GenerateAccessor.class);
            if (annotation == null) {
                continue;
            }

            final String cleanName = resolveCleanName(method, annotation);
            if (cleanName == null) {
                continue;
            }

            final String mixinMethodName = method.getSimpleName().toString();
            final String returnType = processingEnv.getTypeUtils()
                    .erasure(method.getReturnType()).toString();
            final List<ParamEntry> params = collectParameters(method);

            entriesByMixin.computeIfAbsent(mixinClass, k -> new ArrayList<>())
                    .add(new AccessorEntry(cleanName, mixinMethodName, returnType, params));

            resolveMixinTargetOnce(mixinClass, targetByMixin);
        }
    }

    /**
     * Determines the accessor method name: either the explicit
     * {@link GenerateAccessor#name()} or the annotated method's own name.
     *
     * @return the validated name, or {@code null} if invalid
     */
    private String resolveCleanName(final ExecutableElement method,
                                    final GenerateAccessor annotation) {
        final String annotatedName = annotation.name();
        if (annotatedName.isBlank()) {
            return method.getSimpleName().toString();
        }

        final String trimmed = annotatedName.trim();
        if (!SourceVersion.isIdentifier(trimmed) || SourceVersion.isKeyword(trimmed)) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@GenerateAccessor name must be a valid Java identifier: \"" + annotatedName + "\"",
                    method);
            return null;
        }
        return trimmed;
    }

    /**
     * Collects erased parameter types with positional names ({@code p0}, {@code p1}, …)
     * so the generated interface compiles regardless of {@code -parameters}.
     */
    private List<ParamEntry> collectParameters(final ExecutableElement method) {
        final List<? extends VariableElement> params = method.getParameters();
        final List<ParamEntry> result = new ArrayList<>(params.size());
        for (int i = 0; i < params.size(); i++) {
            final String type = processingEnv.getTypeUtils()
                    .erasure(params.get(i).asType()).toString();
            result.add(new ParamEntry(type, "p" + i));
        }
        return result;
    }

    /**
     * Resolves the {@code @Mixin} target for the given class, caching the result.
     */
    private void resolveMixinTargetOnce(final TypeElement mixinClass,
                                        final Map<TypeElement, MixinTarget> targetByMixin) {
        if (targetByMixin.containsKey(mixinClass)) {
            return;
        }

        final MixinTarget target = MixinTargetResolver.resolve(
                mixinClass, processingEnv, "@GenerateAccessor");

        if (target == null) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@GenerateAccessor: could not determine @Mixin target for "
                            + mixinClass.getQualifiedName(),
                    mixinClass);
            return;
        }
        targetByMixin.put(mixinClass, target);
    }

    // ── Code generation ─────────────────────────────────────────────────────

    /**
     * Emits one accessor interface per mixin class.
     */
    private void generateAccessorInterfaces(final Map<TypeElement, List<AccessorEntry>> entriesByMixin,
                                            final Map<TypeElement, MixinTarget> targetByMixin) {

        for (final var entry : entriesByMixin.entrySet()) {
            final TypeElement mixinClass = entry.getKey();
            final List<AccessorEntry> entries = entry.getValue();
            final MixinTarget target = targetByMixin.get(mixinClass);
            if (target == null) {
                continue;
            }

            final String mixinPackage = packageName(mixinClass);
            final String accessorPackage = deriveAccessorPackage(mixinPackage);
            final String accessorClassName = target.simpleName() + "Accessor";
            final String accessorFqn = accessorPackage.isEmpty()
                    ? accessorClassName
                    : accessorPackage + "." + accessorClassName;

            writeAccessorFile(accessorFqn, mixinClass, accessorPackage, accessorClassName, entries);
        }
    }

    /**
     * Creates the source file and writes the accessor interface.
     */
    private void writeAccessorFile(final String fqn,
                                   final TypeElement originatingElement,
                                   final String pkg,
                                   final String className,
                                   final List<AccessorEntry> entries) {
        try {
            final JavaFileObject file = processingEnv.getFiler()
                    .createSourceFile(fqn, originatingElement);
            try (final Writer writer = file.openWriter()) {
                writeAccessorInterface(writer, pkg, className, entries);
            }
        } catch (final IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Failed to generate " + fqn + ": " + e.getMessage(),
                    originatingElement);
        }
    }

    /**
     * Writes the accessor interface source code to the given writer.
     */
    private static void writeAccessorInterface(final Writer writer,
                                               final String pkg,
                                               final String className,
                                               final List<AccessorEntry> entries) throws IOException {
        if (!pkg.isEmpty()) {
            writer.write("package " + pkg + ";\n\n");
        }

        writer.write("import eu.lschreiber.laminateapi.accessor.LaminateAccessors;\n\n");
        writer.write("/**\n");
        writer.write(" * Generated by {@link eu.lschreiber.laminateapi.accessor.GenerateAccessorProcessor}.\n");
        writer.write(" * Do not edit manually.\n");
        writer.write(" */\n");
        writer.write("public interface " + className + " {\n\n");

        writeMethodDeclarations(writer, entries);
        writeMappingsArray(writer, entries, className);

        writer.write("}\n");
    }

    /**
     * Writes abstract method declarations for each accessor entry.
     */
    private static void writeMethodDeclarations(final Writer writer,
                                                final List<AccessorEntry> entries) throws IOException {
        for (final AccessorEntry entry : entries) {
            final String paramList = entry.params().stream()
                    .map(p -> p.type() + " " + p.name())
                    .collect(Collectors.joining(", "));
            writer.write("    " + entry.returnType() + " " + entry.cleanName()
                    + "(" + paramList + ");\n\n");
        }
    }

    /**
     * Writes the static {@code of(Object)} factory method with its method-mapping array.
     */
    private static void writeMappingsArray(final Writer writer,
                                           final List<AccessorEntry> entries,
                                           final String className) throws IOException {
        final StringBuilder mappings = new StringBuilder("new String[]{");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                mappings.append(", ");
            }
            mappings.append('"').append(entries.get(i).cleanName()).append('"');
            mappings.append(", ");
            mappings.append('"').append(entries.get(i).mixinMethodName()).append('"');
        }
        mappings.append('}');

        writer.write("    static " + className + " of(Object target) {\n");
        writer.write("        return LaminateAccessors.create(" + className
                + ".class, target, " + mappings + ");\n");
        writer.write("    }\n");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String packageName(final TypeElement element) {
        return processingEnv.getElementUtils()
                .getPackageOf(element).getQualifiedName().toString();
    }

    /**
     * Derives the accessor output package from the mixin package.
     *
     * <p>The accessor is placed in a sibling {@code accessor} package — i.e. the
     * parent of the mixin package with {@code .accessor} appended.</p>
     *
     * <p>Example: {@code com.example.mixins} → {@code com.example.accessor}</p>
     */
    static String deriveAccessorPackage(final String mixinPackage) {
        if (mixinPackage.isEmpty()) {
            return "accessor";
        }
        final int lastDot = mixinPackage.lastIndexOf('.');
        if (lastDot < 0) {
            return "accessor";
        }
        return mixinPackage.substring(0, lastDot) + ".accessor";
    }

    // ── Data records ────────────────────────────────────────────────────────

    private record ParamEntry(String type, String name) {
    }

    private record AccessorEntry(String cleanName, String mixinMethodName,
                                 String returnType, List<ParamEntry> params) {
    }
}
