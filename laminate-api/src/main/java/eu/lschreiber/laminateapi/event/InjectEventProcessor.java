package eu.lschreiber.laminateapi.event;

import eu.lschreiber.laminateapi.internal.MixinTargetResolver;
import eu.lschreiber.laminateapi.internal.MixinTargetResolver.MixinTarget;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.FilerException;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

/**
 * Annotation processor for {@link InjectEvent}.
 *
 * <p>For each annotated marker method, the processor:</p>
 * <ol>
 *   <li>Validates the mixin context and annotation attributes.</li>
 *   <li>Reads the {@link Rewrite @Rewrite} descriptor.</li>
 *   <li>Generates a companion Mixin class that injects at the specified point
 *       and fires the given Bukkit event via {@link LaminateEventBridge}.</li>
 *   <li>In the final processing round, writes a Mixin JSON configuration file
 *       (e.g. {@code mixins.testplugin.generated.json}) that lists all
 *       generated companion classes. The developer must add this filename
 *       to the {@code mixins} list in {@code plugin.yml} once.</li>
 * </ol>
 *
 * <p>The config filename is derived from the mixin package: trailing
 * {@code .mixins} or {@code .mixin} segments are stripped and the last
 * remaining segment is used as the identifier
 * (e.g. {@code eu.lschreiber.testplugin.mixins} →
 * {@code mixins.testplugin.generated.json}).</p>
 */
@SupportedAnnotationTypes("eu.lschreiber.laminateapi.event.InjectEvent")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class InjectEventProcessor extends AbstractProcessor {

    /**
     * Collects generated companion mixin classes across processing rounds,
     * grouped by the target config filename.
     */
    private final Map<String, List<MixinConfigEntry>> pendingConfigs = new LinkedHashMap<>();

    /**
     * Validates that {@code At.INVOKE} injections specify a non-blank target.
     *
     * @throws IllegalArgumentException if {@code at=INVOKE} but {@code target()} is blank
     */
    private static void validateInvokeTarget(final InjectEvent annotation) {
        if (annotation.at() == At.INVOKE) {
            final String invokeTarget = annotation.target();
            if (invokeTarget == null || invokeTarget.isBlank()) {
                throw new IllegalArgumentException(
                    "at=At.INVOKE requires a non-blank target() descriptor");
            }
        }
    }

    // ── Per-method processing ───────────────────────────────────────────────

    /**
     * Resolves the JVM method descriptor from either the {@code method} string
     * shorthand or the {@code rewrite} annotation on {@link InjectEvent}.
     *
     * <ul>
     *   <li>If {@code method} is non-blank it is returned as-is (simple name or
     *       full JVM descriptor — Mixin accepts both).</li>
     *   <li>If {@code rewrite} carries a non-blank {@link Rewrite#method()}
     *       the descriptor is built from the typed fields.</li>
     *   <li>Providing both or neither is a compile-time error.</li>
     * </ul>
     *
     * @return the descriptor, or {@code null} if validation failed
     */
    private static String buildDescriptorFromRewrite(final InjectEvent annotation) {
        final String methodStr = annotation.method();
        final boolean hasString = methodStr != null && !methodStr.isBlank();

        Rewrite rewrite = null;
        boolean hasRewrite = false;
        try {
            rewrite = annotation.methodRewrite();
            hasRewrite = rewrite != null && !rewrite.method().isBlank();
        } catch (final Exception ignored) {
            // annotation.methodRewrite() can throw at compile time; treat as absent
        }

        if (hasString && hasRewrite) {
            throw new IllegalArgumentException(
                "specify either method (String) or methodRewrite (@Rewrite), not both");
        }
        if (!hasString && !hasRewrite) {
            throw new IllegalArgumentException(
                "one of method (String) or methodRewrite (@Rewrite) must be set");
        }

        if (hasString) {
            return methodStr;
        }

        return buildDescriptorFromRewriteAnnotation(rewrite);
    }

    /**
     * Converts a {@link Rewrite} annotation into the full JVM method descriptor
     * by delegating to {@link RewriteProcessor#toDescriptor(Rewrite)}.
     *
     * @throws IllegalArgumentException if the descriptor cannot be built
     */
    private static String buildDescriptorFromRewriteAnnotation(final Rewrite rewrite) {
        try {
            return RewriteProcessor.toDescriptor(rewrite);
        } catch (final Exception e) {
            throw new IllegalArgumentException("could not read @Rewrite: " + e.getMessage(), e);
        }
    }

    /**
     * Writes the companion Mixin class source code.
     */
    private static void writeCompanionMixin(
        final Writer writer,
        final String pkg,
        final String className,
        final InjectionContext ctx
    ) throws IOException {
        writePackageAndImports(writer, pkg, ctx.voidReturn());
        writeMixinAnnotation(writer, ctx.target().fqn(), className);
        writeInjectMethod(writer, className, ctx);
        writer.write("}\n");
    }

    private static void writePackageAndImports(
        final Writer writer,
        final String pkg,
        final boolean voidReturn
    ) throws IOException {
        if (!pkg.isEmpty()) {
            writer.write("package " + pkg + ";\n\n");
        }

        writer.write("import org.spongepowered.asm.mixin.Mixin;\n");
        writer.write("import org.spongepowered.asm.mixin.injection.At;\n");
        writer.write("import org.spongepowered.asm.mixin.injection.Inject;\n");

        if (voidReturn) {
            writer.write("import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;\n\n");
        } else {
            writer.write("import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;\n\n");
        }
    }

    // ── @Rewrite / String → JVM descriptor ────────────────────────────────

    private static void writeMixinAnnotation(
        final Writer writer,
        final String targetFqn,
        final String className
    ) throws IOException {
        writer.write("/** Generated by InjectEventProcessor. Do not edit. */\n");
        writer.write("@Mixin(targets = \"" + targetFqn + "\")\n");
        writer.write("abstract class " + className + " {\n\n");
    }

    private static void writeInjectMethod(
        final Writer writer,
        final String className,
        final InjectionContext ctx
    ) throws IOException {
        final String callbackType = ctx.voidReturn() ? "CallbackInfo" : "CallbackInfoReturnable";
        final String atExpr = buildAtExpression(ctx.annotation().at(), ctx.annotation().target());
        final String paramList = buildParamList(ctx.params(), callbackType);
        final String extraArgsLiteral = buildExtraArgsLiteral(ctx.params());

        writer.write("    @Inject(method = \"" + ctx.jvmDescriptor()
            + "\", at = " + atExpr
            + ", cancellable = " + ctx.annotation().cancellable() + ")\n");
        writer.write("    private void laminate$injectEvent_" + sanitize(className)
            + "(" + paramList + ") {\n");

        if (ctx.annotation().cancellable()) {
            writeCancellableBody(writer, ctx.eventFqn(), extraArgsLiteral);
        } else {
            writeFireOnlyBody(writer, ctx.eventFqn(), extraArgsLiteral);
        }

        writer.write("    }\n");
    }


    // ── Event type resolution ───────────────────────────────────────────────

    private static void writeCancellableBody(
        final Writer writer,
        final String eventFqn,
        final String extraArgsLiteral
    ) throws IOException {
        writer.write("        org.bukkit.event.Event _event =\n");
        writer.write("            eu.lschreiber.laminateapi.event.LaminateEventBridge.fireAndReturn(\n");
        writer.write("                \"" + eventFqn + "\", (Object)(Object)this, " + extraArgsLiteral + ");\n");
        writer.write("        if (_event instanceof org.bukkit.event.Cancellable\n");
        writer.write("                && ((org.bukkit.event.Cancellable) _event).isCancelled()) {\n");
        writer.write("            ci.cancel();\n");
        writer.write("        }\n");
    }

    // ── Code generation ─────────────────────────────────────────────────────

    private static void writeFireOnlyBody(
        final Writer writer,
        final String eventFqn,
        final String extraArgsLiteral
    ) throws IOException {
        writer.write("        eu.lschreiber.laminateapi.event.LaminateEventBridge.fire(\n");
        writer.write("            \"" + eventFqn + "\", (Object)(Object)this, " + extraArgsLiteral + ");\n");
    }

    private static String buildAtExpression(final At at, final String invokeTarget) {
        if (at == At.INVOKE) {
            return "@At(value = \"INVOKE\", target = \"" + invokeTarget + "\")";
        }
        return "@At(\"" + at.name() + "\")";
    }

    private static String buildParamList(
        final List<ParamEntry> params,
        final String callbackType
    ) {
        if (params.isEmpty()) {
            return callbackType + " ci";
        }
        return params.stream()
            .map(p -> p.type() + " " + p.name())
            .collect(Collectors.joining(", "))
            + ", " + callbackType + " ci";
    }

    private static String buildExtraArgsLiteral(final List<ParamEntry> params) {
        if (params.isEmpty()) {
            return "new Object[0]";
        }
        return "new Object[]{"
            + params.stream().map(ParamEntry::name).collect(Collectors.joining(", "))
            + "}";
    }

    /**
     * Derives the mixin config filename from the mixin package.
     *
     * <p>Strips a trailing {@code .mixins} or {@code .mixin} segment, then
     * takes the last remaining segment as the plugin identifier.</p>
     *
     * <p>Examples:</p>
     * <ul>
     *   <li>{@code eu.lschreiber.testplugin.mixins} → {@code mixins.testplugin.generated.json}</li>
     *   <li>{@code com.example.myplugin.mixin} → {@code mixins.myplugin.generated.json}</li>
     *   <li>{@code com.example.myplugin} → {@code mixins.myplugin.generated.json}</li>
     * </ul>
     */
    static String resolveConfigFileName(final String mixinPackage) {
        if (mixinPackage.isEmpty()) {
            return "mixins.generated.json";
        }

        final String[] segments = mixinPackage.split("\\.");

        final int end;
        final String last = segments[segments.length - 1];
        if (segments.length > 1 && ("mixins".equals(last) || "mixin".equals(last))) {
            end = segments.length - 1;
        } else {
            end = segments.length;
        }

        final String identifier = segments[end - 1];
        return "mixins." + identifier + ".generated.json";
    }

    /**
     * Writes the JSON content of a mixin config file.
     *
     * <p>When all entries share the same package, the {@code "package"} field
     * is set and simple class names are used. Otherwise, fully-qualified names
     * are written without a package field.</p>
     */
    private static void writeMixinJson(
        final Writer writer,
        final List<MixinConfigEntry> entries
    ) throws IOException {
        final Map<String, List<String>> byPackage = new LinkedHashMap<>();
        for (final MixinConfigEntry e : entries) {
            byPackage.computeIfAbsent(e.pkg(), k -> new ArrayList<>()).add(e.simpleClassName());
        }

        writer.write("{\n");
        writer.write("  \"required\": true,\n");
        writer.write("  \"compatibilityLevel\": \"JAVA_21\",\n");

        if (byPackage.size() == 1) {
            final var single = byPackage.entrySet().iterator().next();
            writer.write("  \"package\": \"" + single.getKey() + "\",\n");
            writeMixinsArray(writer, single.getValue());
        } else {
            final List<String> fqns = new ArrayList<>();
            for (final var pkgEntry : byPackage.entrySet()) {
                for (final String cls : pkgEntry.getValue()) {
                    fqns.add(pkgEntry.getKey() + "." + cls);
                }
            }
            writeMixinsArray(writer, fqns);
        }

        writer.write("}\n");
    }

    private static void writeMixinsArray(
        final Writer writer,
        final List<String> classNames
    ) throws IOException {
        writer.write("  \"mixins\": [\n");
        for (int i = 0; i < classNames.size(); i++) {
            writer.write("    \"" + classNames.get(i) + "\"");
            if (i < classNames.size() - 1) {
                writer.write(",");
            }
            writer.write("\n");
        }
        writer.write("  ]\n");
    }

    static boolean isVoidReturn(final String jvmDescriptor) {
        final int closeParenIdx = jvmDescriptor.lastIndexOf(')');
        if (closeParenIdx < 0) {
            // Bare method name without a return-type descriptor (e.g. "tick").
            // Mixin resolves the target by name; we cannot determine the return
            // type at compile time.  Default to void (CallbackInfo) because the
            // vast majority of NMS injection targets are void methods.
            // If the target is non-void, use a full JVM descriptor
            // (e.g. "tick(Ljava/util/function/BooleanSupplier;)V") or @Rewrite.
            return true;
        }
        final String returnDesc = jvmDescriptor.substring(closeParenIdx + 1);
        return "V".equals(returnDesc);
    }

    static String sanitize(final String name) {
        return name.replace('$', '_').replace('.', '_');
    }

    @Override
    public boolean process(
        final Set<? extends TypeElement> annotations,
        final RoundEnvironment roundEnv
    ) {

        if (!annotations.isEmpty()) {
            for (final Element element : roundEnv.getElementsAnnotatedWith(InjectEvent.class)) {
                if (!(element instanceof ExecutableElement method)) {
                    continue;
                }
                processAnnotatedMethod(method);
            }
        }

        if (roundEnv.processingOver() && !pendingConfigs.isEmpty()) {
            writePendingMixinConfigs();
        }

        return !annotations.isEmpty();
    }

    // ── Mixin config JSON generation ────────────────────────────────────────

    /**
     * Validates and processes a single {@code @InjectEvent}-annotated method.
     */
    private void processAnnotatedMethod(final ExecutableElement method) {
        final TypeElement mixinClass = (TypeElement) method.getEnclosingElement();
        final MixinTarget target = resolveMixinTargetOrError(mixinClass);
        if (target == null) {
            return;
        }

        final InjectEvent annotation = method.getAnnotation(InjectEvent.class);
        if (annotation == null) {
            return;
        }

        final String jvmDescriptor;
        try {
            validateInvokeTarget(annotation);
            jvmDescriptor = buildDescriptorFromRewrite(annotation);
        } catch (final IllegalArgumentException e) {
            error("@InjectEvent: " + e.getMessage(), method);
            return;
        }
        if (jvmDescriptor == null) {
            return;
        }

        final TypeMirror eventTypeMirror = resolveEventType(method, annotation);
        if (eventTypeMirror == null) {
            return;
        }

        final TypeElement eventTypeElement = asTypeElement(eventTypeMirror);
        if (eventTypeElement == null) {
            error("@InjectEvent: event type could not be resolved", method);
            return;
        }

        final String eventFqn = eventTypeElement.getQualifiedName().toString();
        warnIfNotCancellable(annotation, eventTypeElement, eventFqn, method);

        final InjectionContext ctx = new InjectionContext(
            target, annotation, jvmDescriptor, eventFqn,
            collectParameters(method), isVoidReturn(jvmDescriptor));

        generateCompanionMixin(mixinClass, method, ctx);
    }

    /**
     * Resolves the {@code @Mixin} target or emits an error.
     */
    private MixinTarget resolveMixinTargetOrError(final TypeElement mixinClass) {
        final MixinTarget target = MixinTargetResolver.resolve(
            mixinClass, processingEnv, "@InjectEvent");
        if (target == null) {
            error("@InjectEvent: could not determine @Mixin target for "
                + mixinClass.getQualifiedName(), mixinClass);
        }
        return target;
    }

    /**
     * Emits a warning if {@code cancellable=true} but the event class does not
     * implement {@code Cancellable}.
     */
    private void warnIfNotCancellable(
        final InjectEvent annotation,
        final TypeElement eventTypeElement,
        final String eventFqn,
        final ExecutableElement method
    ) {
        if (annotation.cancellable() && !implementsCancellable(eventTypeElement)) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                "@InjectEvent: cancellable=true but " + eventFqn
                    + " does not implement Cancellable",
                method);
        }
    }

    /**
     * Resolves the event class specified in {@link InjectEvent#event()},
     * handling the {@link MirroredTypeException} that occurs at compile time.
     */
    private TypeMirror resolveEventType(
        final ExecutableElement method,
        final InjectEvent annotation
    ) {
        try {
            final Class<?> eventClass = annotation.event();
            final TypeElement te = processingEnv.getElementUtils()
                .getTypeElement(eventClass.getCanonicalName());
            if (te == null) {
                error("@InjectEvent: event class " + eventClass.getCanonicalName()
                    + " could not be resolved", method);
                return null;
            }
            return te.asType();
        } catch (final MirroredTypeException mte) {
            return mte.getTypeMirror();
        } catch (final Exception e) {
            error("@InjectEvent: could not resolve event class: " + e.getMessage(), method);
            return null;
        }
    }

    /**
     * Generates and writes the companion Mixin class for one injection point.
     */
    private void generateCompanionMixin(
        final TypeElement mixinClass,
        final ExecutableElement method,
        final InjectionContext ctx
    ) {
        final String mixinPackage = packageName(mixinClass);
        final String sanitizedName = sanitize(method.getSimpleName().toString());
        final String generatedClassName = ctx.target().simpleName()
            + "InjectEvent_" + sanitizedName;
        final String generatedFqn = mixinPackage.isEmpty()
            ? generatedClassName
            : mixinPackage + "." + generatedClassName;

        try {
            final JavaFileObject file = processingEnv.getFiler()
                .createSourceFile(generatedFqn, mixinClass);
            try (final Writer writer = file.openWriter()) {
                writeCompanionMixin(writer, mixinPackage, generatedClassName, ctx);
            }
        } catch (final IOException e) {
            error("Failed to generate " + generatedFqn + ": " + e.getMessage(), mixinClass);
            return;
        }

        // Register the generated class for the auto-generated mixin config JSON
        final String configFileName = resolveConfigFileName(mixinPackage);
        pendingConfigs.computeIfAbsent(configFileName, k -> new ArrayList<>())
            .add(new MixinConfigEntry(mixinPackage, generatedClassName));

        // Maybe add back in later
        // processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
        //     "@InjectEvent generated " + generatedFqn, method);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Writes all pending mixin config JSON files to {@code CLASS_OUTPUT}.
     * Called once in the final processing round.
     */
    private void writePendingMixinConfigs() {
        for (final var entry : pendingConfigs.entrySet()) {
            writeMixinConfigJson(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Writes a single mixin config JSON resource file.
     *
     * @param fileName the resource filename (e.g. {@code "mixins.testplugin.generated.json"})
     * @param entries  the generated companion mixin classes to include
     */
    private void writeMixinConfigJson(
        final String fileName,
        final List<MixinConfigEntry> entries
    ) {
        try {
            final FileObject resource = processingEnv.getFiler()
                .createResource(StandardLocation.CLASS_OUTPUT, "", fileName);
            try (final Writer writer = resource.openWriter()) {
                writeMixinJson(writer, entries);
            }
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                "@InjectEvent generated mixin config: " + fileName
                    + " (add to plugin.yml mixins list)");
        } catch (final FilerException e) {
            // Resource already exists from a previous incremental build — skip silently
        } catch (final IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                "@InjectEvent: failed to write mixin config " + fileName + ": " + e.getMessage());
        }
    }

    private boolean implementsCancellable(final TypeElement eventTypeElement) {
        for (final TypeMirror iface : eventTypeElement.getInterfaces()) {
            final Element el = processingEnv.getTypeUtils().asElement(iface);
            if (el instanceof TypeElement te
                && te.getQualifiedName().contentEquals("org.bukkit.event.Cancellable")) {
                return true;
            }
        }
        return false;
    }

    private TypeElement asTypeElement(final TypeMirror mirror) {
        final Element element = processingEnv.getTypeUtils().asElement(mirror);
        return (element instanceof TypeElement te) ? te : null;
    }

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

    private String packageName(final TypeElement element) {
        return processingEnv.getElementUtils()
            .getPackageOf(element).getQualifiedName().toString();
    }

    private void error(final String message, final Element element) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    // ── Data records ────────────────────────────────────────────────────────

    private record ParamEntry(String type, String name) {
    }

    /**
     * A generated companion mixin class entry for the mixin config JSON.
     *
     * @param pkg             the package of the generated class
     * @param simpleClassName the simple class name (e.g. {@code "ServerLevelInjectEvent_testPlugin_onTick"})
     */
    private record MixinConfigEntry(String pkg, String simpleClassName) {
    }

    /**
     * Bundles the validated context for a single injection point, avoiding
     * long parameter lists on code-generation methods.
     */
    private record InjectionContext(
        MixinTarget target,
        InjectEvent annotation,
        String jvmDescriptor,
        String eventFqn,
        List<ParamEntry> params,
        boolean voidReturn) {
    }
}
