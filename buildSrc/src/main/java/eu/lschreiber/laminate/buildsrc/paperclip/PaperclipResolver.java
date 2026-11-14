package eu.lschreiber.laminate.buildsrc.paperclip;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves Paperclip downloads from PaperMC's build APIs. */
public final class PaperclipResolver {

    public static final String USER_AGENT =
            "laminate-standalone/0.1 (+https://github.com/henceiusegentoo/laminate-standalone)";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private PaperclipResolver() {
    }

    public static PaperclipResolution resolve(String paperVersion, String paperBuild) {
        RuntimeException v3Failure;
        try {
            return resolveFillV3(paperVersion, paperBuild);
        } catch (RuntimeException e) {
            v3Failure = e;
        }

        try {
            return resolveLegacyV2(paperVersion, paperBuild);
        } catch (RuntimeException v2Failure) {
            throw new IllegalStateException(
                    "Could not resolve a paperclip build for paperVersion=" + paperVersion
                            + (paperBuild != null ? " paperBuild=" + paperBuild : "") + ". "
                            + "Fill v3 error: " + v3Failure.getMessage() + "; "
                            + "legacy v2 error: " + v2Failure.getMessage(), v2Failure);
        }
    }

    private static PaperclipResolution resolveFillV3(String version, String build) {
        String url = "https://fill.papermc.io/v3/projects/paper/versions/" + version + "/builds/"
                + (build != null ? build : "latest");
        String body = get(url);
        String topLevel = topLevelKeysOnly(body);
        String id = requireNumber(topLevel, "id", url);
        String channel = optionalString(topLevel, "channel", "UNKNOWN");
        String downloads = requireObject(body, "downloads", url);
        String serverDefault = requireObject(downloads, "server:default", url);
        String checksums = requireObject(serverDefault, "checksums", url);
        String sha256 = requireString(checksums, "sha256", url);
        String downloadUrl = requireString(serverDefault, "url", url);
        return new PaperclipResolution(id, channel, downloadUrl, sha256, "Fill v3");
    }

    private static PaperclipResolution resolveLegacyV2(String version, String build) {
        String resolvedBuild = build;
        if (resolvedBuild == null) {
            String listUrl = "https://api.papermc.io/v2/projects/paper/versions/" + version + "/builds";
            String listBody = get(listUrl);
            String buildsArray = requireArray(listBody, "builds", listUrl);
            int max = -1;
            for (String buildObject : extractTopLevelObjects(buildsArray)) {
                String buildNumber = extractNumber(buildObject, "build");
                if (buildNumber != null) {
                    max = Math.max(max, Integer.parseInt(buildNumber));
                }
            }
            if (max < 0) {
                throw new IllegalStateException("'builds' array empty for version " + version + " at " + listUrl);
            }
            resolvedBuild = String.valueOf(max);
        }

        String buildUrl = "https://api.papermc.io/v2/projects/paper/versions/" + version + "/builds/" + resolvedBuild;
        String body = get(buildUrl);
        String channel = optionalString(body, "channel", "UNKNOWN");
        String downloads = requireObject(body, "downloads", buildUrl);
        String application = requireObject(downloads, "application", buildUrl);
        String name = requireString(application, "name", buildUrl);
        String sha256 = requireString(application, "sha256", buildUrl);
        String downloadUrl = buildUrl + "/downloads/" + name;
        return new PaperclipResolution(resolvedBuild, channel, downloadUrl, sha256, "legacy v2");
    }

    private static String get(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("Request to " + url + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Request to " + url + " was interrupted", e);
        }
        if (response.statusCode() / 100 != 2) {
            String body = response.body() == null ? "" : response.body();
            String snippet = body.substring(0, Math.min(300, body.length()));
            throw new IllegalStateException("HTTP " + response.statusCode() + " from " + url + ": " + snippet);
        }
        return response.body();
    }

    private static String requireObject(String json, String key, String url) {
        String v = extractBalanced(json, key, '{', '}');
        if (v == null) {
            throw new IllegalStateException("Response from " + url + " is missing expected object field '" + key + "'");
        }
        return v;
    }

    private static String requireArray(String json, String key, String url) {
        String v = extractBalanced(json, key, '[', ']');
        if (v == null) {
            throw new IllegalStateException("Response from " + url + " is missing expected array field '" + key + "'");
        }
        return v;
    }

    private static String requireString(String json, String key, String url) {
        String v = extractString(json, key);
        if (v == null) {
            throw new IllegalStateException("Response from " + url + " is missing expected string field '" + key + "'");
        }
        return v;
    }

    private static String requireNumber(String json, String key, String url) {
        String v = extractNumber(json, key);
        if (v == null) {
            throw new IllegalStateException("Response from " + url + " is missing expected numeric field '" + key + "'");
        }
        return v;
    }

    private static String optionalString(String json, String key, String fallback) {
        String v = extractString(json, key);
        return v != null ? v : fallback;
    }

    private static String extractBalanced(String json, String key, char open, char close) {
        String marker = "\"" + key + "\"";
        int keyIdx = json.indexOf(marker);
        if (keyIdx < 0) {
            return null;
        }
        int colonIdx = json.indexOf(':', keyIdx + marker.length());
        if (colonIdx < 0) {
            return null;
        }
        int start = json.indexOf(open, colonIdx);
        if (start < 0) {
            return null;
        }
        int depth = 0;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return json.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private static List<String> extractTopLevelObjects(String arrayJson) {
        List<String> objects = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        int start = -1;
        for (int i = 0; i < arrayJson.length(); i++) {
            char c = arrayJson.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    objects.add(arrayJson.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return objects;
    }

    private static String topLevelKeysOnly(String json) {
        StringBuilder sb = new StringBuilder(json.length());
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                sb.append(depth <= 1 ? c : ' ');
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                sb.append(depth <= 1 ? c : ' ');
            } else if (c == '{' || c == '[') {
                depth++;
                sb.append(depth <= 1 ? c : ' ');
            } else if (c == '}' || c == ']') {
                sb.append(depth <= 1 ? c : ' ');
                depth--;
            } else {
                sb.append(depth <= 1 ? c : ' ');
            }
        }
        return sb.toString();
    }

    private static String extractString(String json, String key) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
        Matcher m = p.matcher(json);
        if (!m.find()) {
            return null;
        }
        return unescape(m.group(1));
    }

    private static String extractNumber(String json, String key) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)");
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                switch (n) {
                    case '"': sb.append('"'); i += 2; break;
                    case '\\': sb.append('\\'); i += 2; break;
                    case '/': sb.append('/'); i += 2; break;
                    case 'n': sb.append('\n'); i += 2; break;
                    case 't': sb.append('\t'); i += 2; break;
                    case 'r': sb.append('\r'); i += 2; break;
                    case 'b': sb.append('\b'); i += 2; break;
                    case 'f': sb.append('\f'); i += 2; break;
                    case 'u':
                        String hex = s.substring(i + 2, i + 6);
                        sb.append((char) Integer.parseInt(hex, 16));
                        i += 6;
                        break;
                    default:
                        sb.append(n);
                        i += 2;
                        break;
                }
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }
}
