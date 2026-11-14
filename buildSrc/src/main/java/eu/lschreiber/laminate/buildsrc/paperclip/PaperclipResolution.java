package eu.lschreiber.laminate.buildsrc.paperclip;

/**
 * Result of resolving a Paperclip build.
 *
 * @param buildNumber resolved build number
 * @param channel resolved release channel
 * @param downloadUrl resolved download URL, or {@code null} when loaded from cache
 * @param sha256 resolved SHA-256 checksum
 * @param source resolution source
 */
public record PaperclipResolution(
        String buildNumber,
        String channel,
        String downloadUrl,
        String sha256,
        String source
) {
}
