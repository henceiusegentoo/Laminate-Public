package eu.lschreiber.laminate.buildsrc.paperclip;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Downloads and verifies the Paperclip jar for the configured Paper version. */
public abstract class DownloadPaperclipTask extends DefaultTask {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Input
    public abstract Property<String> getPaperVersion();

    @Input
    @Optional
    public abstract Property<String> getPaperBuild();

    @Internal
    public abstract DirectoryProperty getOutputDirectory();

    private static final String SIDECAR_SUFFIX = ".resolved.properties";

    private PaperclipResolution resolution;

    private PaperclipResolution effectiveResolution() {
        if (resolution != null) {
            return resolution;
        }
        String pinnedBuild = getPaperBuild().getOrNull();
        if (pinnedBuild != null) {
            PaperclipResolution cached = loadCachedResolution(pinnedBuild);
            if (cached != null) {
                resolution = cached;
                return resolution;
            }
        }
        resolution = PaperclipResolver.resolve(getPaperVersion().get(), pinnedBuild);
        getLogger().lifecycle(
                "downloadPaperclip: resolved paperVersion={} -> build {} (channel {}, via {})",
                getPaperVersion().get(), resolution.buildNumber(), resolution.channel(), resolution.source());
        return resolution;
    }

    @Input
    public String getResolvedBuildNumber() {
        return effectiveResolution().buildNumber();
    }

    @Input
    public String getResolvedChannel() {
        return effectiveResolution().channel();
    }

    @Input
    public String getExpectedSha256() {
        return effectiveResolution().sha256();
    }

    /** Returns the output jar path. */
    @OutputFile
    public File getOutputJar() {
        String pinnedBuild = getPaperBuild().getOrNull();
        String buildNumber = pinnedBuild != null ? pinnedBuild : effectiveResolution().buildNumber();
        return outputJarFor(buildNumber);
    }

    private File outputJarFor(String buildNumber) {
        String fileName = "paperclip-" + getPaperVersion().get() + "-" + buildNumber + ".jar";
        return new File(getOutputDirectory().get().getAsFile(), fileName);
    }

    private File sidecarFile(File jar) {
        return new File(jar.getParentFile(), jar.getName() + SIDECAR_SUFFIX);
    }

    private PaperclipResolution loadCachedResolution(String pinnedBuild) {
        File jar = outputJarFor(pinnedBuild);
        File sidecar = sidecarFile(jar);
        if (!jar.isFile() || !sidecar.isFile()) {
            return null;
        }
        Map<String, String> fields;
        try {
            fields = readSidecar(sidecar);
        } catch (IOException e) {
            return null;
        }
        if (!getPaperVersion().get().equals(fields.get("paperVersion"))
                || !pinnedBuild.equals(fields.get("paperBuild"))
                || !pinnedBuild.equals(fields.get("buildNumber"))) {
            return null;
        }
        String sha256 = fields.get("sha256");
        String channel = fields.get("channel");
        if (sha256 == null || channel == null) {
            return null;
        }
        if (!sha256.equalsIgnoreCase(sha256(jar))) {
            return null;
        }
        return new PaperclipResolution(pinnedBuild, channel, null, sha256, "cache (no network)");
    }

    private static Map<String, String> readSidecar(File sidecar) throws IOException {
        Map<String, String> fields = new HashMap<>();
        List<String> lines = Files.readAllLines(sidecar.toPath(), StandardCharsets.UTF_8);
        for (String line : lines) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                fields.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        return fields;
    }

    private void writeSidecar(File jar, PaperclipResolution resolved) {
        String pinnedBuild = getPaperBuild().getOrNull();
        String content = "paperVersion=" + getPaperVersion().get() + "\n"
                + "paperBuild=" + (pinnedBuild != null ? pinnedBuild : "latest") + "\n"
                + "buildNumber=" + resolved.buildNumber() + "\n"
                + "channel=" + resolved.channel() + "\n"
                + "sha256=" + resolved.sha256() + "\n";
        try {
            Files.writeString(sidecarFile(jar).toPath(), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            getLogger().warn("downloadPaperclip: could not write resolution cache for {}: {}", jar.getName(), e.getMessage());
        }
    }

    @TaskAction
    public void run() {
        File target = getOutputJar();
        PaperclipResolution resolved = effectiveResolution();
        String expected = resolved.sha256();

        if (target.isFile() && expected.equalsIgnoreCase(sha256(target))) {
            getLogger().lifecycle(
                    "downloadPaperclip: {} (build {} / {}) already present with matching sha256, skipping download.",
                    target.getName(), resolved.buildNumber(), resolved.channel());
            writeSidecar(target, resolved);
            return;
        }

        if (resolved.downloadUrl() == null) {
            resolution = null;
            resolved = PaperclipResolver.resolve(getPaperVersion().get(), getPaperBuild().getOrNull());
            resolution = resolved;
            expected = resolved.sha256();
            getLogger().lifecycle(
                    "downloadPaperclip: cached resolution was unusable (file missing or changed on disk); "
                            + "re-resolved paperVersion={} -> build {} (channel {}, via {})",
                    getPaperVersion().get(), resolved.buildNumber(), resolved.channel(), resolved.source());
            target = getOutputJar();
        }

        File parent = target.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new GradleException("downloadPaperclip: could not create output directory " + parent);
        }

        getLogger().lifecycle("downloadPaperclip: downloading {} from {}", target.getName(), resolved.downloadUrl());

        File tmp = new File(parent, target.getName() + ".part");
        try {
            Files.deleteIfExists(tmp.toPath());
        } catch (IOException e) {
            throw new GradleException("downloadPaperclip: could not clean up stale temp file " + tmp, e);
        }

        download(resolved.downloadUrl(), tmp);

        String actual = sha256(tmp);
        if (!expected.equalsIgnoreCase(actual)) {
            try {
                Files.deleteIfExists(tmp.toPath());
            } catch (IOException ignored) {
            }
            throw new GradleException(
                    "downloadPaperclip: sha256 mismatch for " + target.getName() + ": expected " + expected
                            + " (from " + resolved.source() + " API) but downloaded file hashed to " + actual
                            + ". Aborting - the download is corrupt or was tampered with.");
        }

        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new GradleException("downloadPaperclip: could not move downloaded file into place: " + e.getMessage(), e);
        }

        writeSidecar(target, resolved);
        getLogger().lifecycle("downloadPaperclip: verified sha256={} for {}", actual, target.getName());
    }

    private static void download(String url, File target) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", PaperclipResolver.USER_AGENT)
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();
        HttpResponse<java.nio.file.Path> response;
        try {
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofFile(target.toPath()));
        } catch (IOException e) {
            throw new GradleException("downloadPaperclip: download from " + url + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GradleException("downloadPaperclip: download from " + url + " was interrupted", e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new GradleException("downloadPaperclip: download failed with HTTP " + response.statusCode() + " from " + url);
        }
    }

    private static String sha256(File file) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new GradleException("SHA-256 not available", e);
        }
        byte[] buffer = new byte[1 << 16];
        try (InputStream input = Files.newInputStream(file.toPath())) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new GradleException("downloadPaperclip: could not hash " + file + ": " + e.getMessage(), e);
        }
        StringBuilder sb = new StringBuilder(64);
        for (byte b : digest.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
