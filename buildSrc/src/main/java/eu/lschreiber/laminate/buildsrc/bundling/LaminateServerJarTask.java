package eu.lschreiber.laminate.buildsrc.bundling;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/** Merges the Paperclip jar with this project's classes and runtime libraries. */
public abstract class LaminateServerJarTask extends DefaultTask {

    private static final String LAMINATE_MAIN_CLASS = "eu.lschreiber.laminate.launcher.LaminateMain";
    private static final String LAMINATE_AGENT_CLASS = "eu.lschreiber.laminate.agent.LaminateAgent";
    private static final String BUNDLER_MAIN_CLASS_ATTRIBUTE = "Bundler-Main-Class";
    private static final String MANIFEST_ENTRY = "META-INF/MANIFEST.MF";

    @InputFile
    public abstract RegularFileProperty getPaperclipJar();

    /**
     * One or more first-party {@code compileJava} output directories, highest merge priority,
     * copied in the order given. Directories are walked recursively; each relative path becomes the
     * jar entry name.
     */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getClassDirectories();

    /**
     * Third-party/inter-module library jars merged in as-is (their contents flattened into the
     * output jar), after {@link #getClassDirectories()} but before {@link #getPaperclipJar()}.
     */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getLibraryJars();

    @OutputFile
    public abstract RegularFileProperty getOutputJar();

    /**
     * <b>Java agent wiring:</b> {@code Premain-Class}/{@code Agent-Class} point at
     * {@code LaminateAgent} (which captures the JVM {@code Instrumentation} handle used by a later
     * phase's mixin bytecode transformation), covering the explicit {@code -javaagent:} and
     * Attach-API loading paths respectively.
     *
     * <p>{@code Launcher-Agent-Class} is deliberately NOT set here (unlike an earlier version of
     * this task), matching the reference fork's manifest exactly (see
     * {@code paper-server/build.gradle.kts}'s {@code createLaminateServerJar}: only
     * Premain-Class/Agent-Class/Can-Retransform-Classes/Can-Redefine-Classes, never
     * Launcher-Agent-Class). Reason, discovered via Phase 2 Task 2's live end-to-end verification:
     * this jar is run via {@code java -jar} AND carries this same manifest as its own agent
     * attributes. If {@code Launcher-Agent-Class} were set, the JVM launcher auto-loads
     * {@code LaminateAgent} (via {@code agentmain()}, which delegates to {@code premain()}) before
     * {@code LaminateMain.main()} ever runs - on <em>every</em> invocation, including the very
     * first, flag-less one. That satisfies {@code LaminateMain}'s "is the agent loaded?" check
     * immediately, so {@code RelaunchHandler.relaunch()} (the mechanism that actually appends the
     * required {@code --add-opens} flags) never fires at all. {@code Launcher-Agent-Class} cannot
     * supply extra JVM flags, so relying on it silently strands the process without
     * {@code --add-opens} forever. Self-relaunch via an explicit {@code -javaagent:}
     * ({@code RelaunchHandler}) must stay the only agent-loading path for this jar.
     */
    @TaskAction
    public void run() throws IOException {
        File paperclipJar = getPaperclipJar().get().getAsFile();
        File output = getOutputJar().get().getAsFile();

        String bundlerMainClass = readPaperclipMainClass(paperclipJar);

        File parent = output.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new GradleException("laminateServerJar: could not create output directory " + parent);
        }

        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.put(Attributes.Name.MAIN_CLASS, LAMINATE_MAIN_CLASS);
        attributes.put(new Attributes.Name(BUNDLER_MAIN_CLASS_ATTRIBUTE), bundlerMainClass);
        attributes.put(new Attributes.Name("Premain-Class"), LAMINATE_AGENT_CLASS);
        attributes.put(new Attributes.Name("Agent-Class"), LAMINATE_AGENT_CLASS);
        attributes.put(new Attributes.Name("Can-Retransform-Classes"), "true");
        attributes.put(new Attributes.Name("Can-Redefine-Classes"), "true");

        populateLibraryPackageManifestAttributes(manifest);

        File tmp = new File(parent, output.getName() + ".part");
        Files.deleteIfExists(tmp.toPath());

        Set<String> written = new LinkedHashSet<>();
        written.add(MANIFEST_ENTRY);

        try (JarOutputStream jarOut = new JarOutputStream(Files.newOutputStream(tmp.toPath()), manifest)) {
            for (File classDirectory : getClassDirectories()) {
                writeClassDirectory(jarOut, classDirectory.toPath(), written);
            }
            for (File libraryJar : getLibraryJars()) {
                writeJarEntries(jarOut, libraryJar, written, true);
            }
            writeJarEntries(jarOut, paperclipJar, written, false);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp.toPath());
            throw e;
        }

        Files.move(tmp.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);

        getLogger().lifecycle("laminateServerJar: wrote {} (Bundler-Main-Class={})", output.getName(), bundlerMainClass);
    }

    private static String readPaperclipMainClass(File paperclipJar) throws IOException {
        try (JarFile jar = new JarFile(paperclipJar)) {
            Manifest manifest = jar.getManifest();
            if (manifest == null) {
                throw new GradleException(
                        "laminateServerJar: " + paperclipJar.getName() + " has no manifest; cannot determine its Main-Class.");
            }
            String mainClass = manifest.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
            if (mainClass == null || mainClass.isBlank()) {
                throw new GradleException(
                        "laminateServerJar: " + paperclipJar.getName() + "'s manifest has no Main-Class attribute.");
            }
            return mainClass;
        }
    }

    private static final List<Attributes.Name> PROPAGATED_PACKAGE_ATTRIBUTES = List.of(
            Attributes.Name.IMPLEMENTATION_TITLE,
            Attributes.Name.IMPLEMENTATION_VERSION,
            Attributes.Name.IMPLEMENTATION_VENDOR,
            Attributes.Name.SPECIFICATION_TITLE,
            Attributes.Name.SPECIFICATION_VERSION,
            Attributes.Name.SPECIFICATION_VENDOR);

    private void populateLibraryPackageManifestAttributes(Manifest manifest) throws IOException {
        Map<String, Attributes> packageSections = manifest.getEntries();
        for (File libraryJar : getLibraryJars()) {
            try (JarFile jar = new JarFile(libraryJar)) {
                Manifest sourceManifest = jar.getManifest();
                if (sourceManifest == null) {
                    continue;
                }
                Attributes propagated = extractPropagatedAttributes(sourceManifest.getMainAttributes());
                if (propagated.isEmpty()) {
                    continue;
                }
                Enumeration<JarEntry> jarEntries = jar.entries();
                while (jarEntries.hasMoreElements()) {
                    JarEntry entry = jarEntries.nextElement();
                    String packageName = classEntryPackage(entry);
                    if (packageName != null) {
                        packageSections.putIfAbsent(packageName, propagated);
                    }
                }
            }
        }
    }

    private static Attributes extractPropagatedAttributes(Attributes source) {
        Attributes result = new Attributes();
        for (Attributes.Name name : PROPAGATED_PACKAGE_ATTRIBUTES) {
            String value = source.getValue(name);
            if (value != null) {
                result.put(name, value);
            }
        }
        return result;
    }

    private static String classEntryPackage(JarEntry entry) {
        if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
            return null;
        }
        String name = entry.getName();
        int lastSlash = name.lastIndexOf('/');
        return lastSlash < 0 ? null : name.substring(0, lastSlash + 1);
    }

    private void writeClassDirectory(JarOutputStream jarOut, Path classesDir, Set<String> written) throws IOException {
        if (!Files.isDirectory(classesDir)) {
            throw new GradleException(
                    "laminateServerJar: class directory does not exist: " + classesDir + " (did compileJava run?)");
        }
        try (Stream<Path> walk = Files.walk(classesDir).sorted()) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                if (path.equals(classesDir)) {
                    continue;
                }
                boolean isDirectory = Files.isDirectory(path);
                String entryName = toEntryName(classesDir, path) + (isDirectory ? "/" : "");
                if (!written.add(entryName)) {
                    if (!isDirectory) {
                        getLogger().warn(
                                "laminateServerJar: unexpected duplicate entry '{}' while merging class "
                                        + "directory {} - an earlier-written entry with the same path wins.",
                                entryName, classesDir);
                    }
                    continue;
                }
                JarEntry entry = new JarEntry(entryName);
                entry.setTime(Files.getLastModifiedTime(path).toMillis());
                jarOut.putNextEntry(entry);
                if (!isDirectory) {
                    Files.copy(path, jarOut);
                }
                jarOut.closeEntry();
            }
        }
    }

    private void writeJarEntries(JarOutputStream jarOut, File sourceJar, Set<String> written, boolean warnOnDuplicate)
           throws IOException {
        try (JarFile jar = new JarFile(sourceJar)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (isExcludedFromMerge(name)) {
                    continue;
                }
                if (!written.add(name)) {
                    if (warnOnDuplicate && !entry.isDirectory()) {
                        getLogger().warn(
                                "laminateServerJar: unexpected duplicate entry '{}' while merging library jar "
                                        + "{} - an earlier-written entry with the same path wins.",
                                name, sourceJar.getName());
                    }
                    continue;
                }
                JarEntry copy = new JarEntry(name);
                copy.setTime(entry.getTime());
                jarOut.putNextEntry(copy);
                if (!entry.isDirectory()) {
                    try (InputStream in = jar.getInputStream(entry)) {
                        in.transferTo(jarOut);
                    }
                }
                jarOut.closeEntry();
            }
        }
    }

    private static boolean isExcludedFromMerge(String entryName) {
        if (entryName.equals(MANIFEST_ENTRY)) {
            return true;
        }
        if (!entryName.startsWith("META-INF/")) {
            return false;
        }
        String upper = entryName.toUpperCase(Locale.ROOT);
        return upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA");
    }

    private static String toEntryName(Path root, Path path) {
        return root.relativize(path).toString().replace(File.separatorChar, '/');
    }
}
