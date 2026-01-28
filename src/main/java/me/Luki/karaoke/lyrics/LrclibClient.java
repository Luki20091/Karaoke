package me.Luki.karaoke.lyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.Luki.karaoke.Karaoke;
import me.Luki.karaoke.util.ProgressListener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

public final class LrclibClient {

    private static final Pattern BRACKETS = Pattern.compile("\\[[^\\]]*\\]");
    private static final Pattern PARENS = Pattern.compile("\\([^\\)]*\\)");
    private static final Pattern FEAT_SUFFIX = Pattern.compile("(?i)\\b(feat\\.?|ft\\.?|featuring)\\b.*$");
    private static final Pattern PROD_SUFFIX = Pattern.compile("(?i)\\b(prod\\.?|produced\\s+by)\\b.*$");

    private final Karaoke plugin;
    private final HttpClient http;

    public LrclibClient(Karaoke plugin) {
        this.plugin = plugin;
        int timeoutSeconds = Math.max(1, plugin.getConfig().getInt("lyrics.timeoutSeconds", 15));
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Builds a compact LRCLIB search query.
     * Example: title="Taco Hemingway - Mix Sałat feat. ...", artist="Taco Hemingway" -> "Taco Hemingway - Mix Sałat".
     */
    public static String buildSearchQuery(String title, String artist) {
        String a = normalizeArtistForSearch(artist);
        String t = normalizeTitleForSearch(title, a);
        if (t == null || t.isBlank()) {
            return null;
        }
        if (a != null && !a.isBlank()) {
            return (a + " - " + t).trim();
        }
        return t.trim();
    }

    private static String normalizeArtistForSearch(String artist) {
        if (artist == null) {
            return null;
        }
        String a = artist.trim();
        if (a.isBlank()) {
            return a;
        }
        String lower = a.toLowerCase(Locale.ROOT);
        if (lower.endsWith("- topic")) {
            a = a.substring(0, a.length() - "- topic".length()).trim();
        }
        return a;
    }

    private static String normalizeTitleForSearch(String title, String artist) {
        if (title == null) {
            return null;
        }
        String t = title.trim();
        if (t.isBlank()) {
            return t;
        }

        // Remove common decorations: [Official Video], (prod. ...), etc.
        t = BRACKETS.matcher(t).replaceAll(" ");
        t = PARENS.matcher(t).replaceAll(" ");

        // Strip anything after separators like '|'
        int pipe = t.indexOf('|');
        if (pipe > 0) {
            t = t.substring(0, pipe).trim();
        }

        // If title is in "Artist - Title" form, remove artist part.
        if (artist != null && !artist.isBlank()) {
            String tl = t.toLowerCase(Locale.ROOT);
            String al = artist.trim().toLowerCase(Locale.ROOT);

            String prefix = al + " - ";
            if (tl.startsWith(prefix)) {
                t = t.substring(prefix.length()).trim();
            } else {
                int dash = t.indexOf(" - ");
                if (dash > 0) {
                    String left = t.substring(0, dash).trim().toLowerCase(Locale.ROOT);
                    if (left.equals(al) || left.contains(al)) {
                        t = t.substring(dash + 3).trim();
                    }
                }
            }
        }

        // Remove trailing feat./prod. segments.
        t = FEAT_SUFFIX.matcher(t).replaceAll("").trim();
        t = PROD_SUFFIX.matcher(t).replaceAll("").trim();

        // Collapse whitespace.
        t = t.replaceAll("\\s{2,}", " ").trim();

        // Keep query reasonably short.
        if (t.length() > 120) {
            t = t.substring(0, 120).trim();
        }

        // Avoid trailing '-' artifacts
        while (t.endsWith("-") || t.endsWith("–") || t.endsWith(":") || t.endsWith("|")) {
            t = t.substring(0, t.length() - 1).trim();
        }

        return t;
    }

    public String searchSyncedLrc(String query) throws Exception {
        return searchSyncedLrc(query, null);
    }

    public String searchSyncedLrc(String query, ProgressListener progress) throws Exception {
        String q = query == null ? "" : query.trim();
        if (q.isBlank()) {
            return null;
        }

        String endpoint = "https://lrclib.net/api/search?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8);

        int timeoutSeconds = Math.max(1, plugin.getConfig().getInt("lyrics.timeoutSeconds", 15));
        int retries = Math.max(0, plugin.getConfig().getInt("lyrics.retries", 1));
        int backoffMs = Math.max(0, plugin.getConfig().getInt("lyrics.retryBackoffMs", 250));

        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (Exception e) {
            return null;
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("User-Agent", getUserAgent())
                .GET()
                .build();

        int attempts = 1 + retries;
        HttpResponse<InputStream> res = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            plugin.debug().http("GET " + uri + (attempts > 1 ? " (attempt " + attempt + "/" + attempts + ")" : ""));
            try {
                res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                int code = res.statusCode();
                if (code == 200) {
                    break;
                }
                if (attempt < attempts && isTransientHttp(code)) {
                    sleepBackoff(backoffMs, attempt);
                    continue;
                }
                return null;
            } catch (java.io.IOException e) {
                if (attempt < attempts) {
                    sleepBackoff(backoffMs, attempt);
                    continue;
                }
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }

        if (res == null || res.statusCode() != 200) {
            return null;
        }

        String body;
        try {
            body = readToString(res, progress);
        } catch (Exception e) {
            return null;
        }

        JsonArray arr;
        try {
            arr = JsonParser.parseString(body).getAsJsonArray();
        } catch (Exception e) {
            return null;
        }

        // Choose first non-empty syncedLyrics
        for (int i = 0; i < arr.size(); i++) {
            JsonObject obj = arr.get(i).getAsJsonObject();
            if (obj == null) {
                continue;
            }
            if (obj.has("syncedLyrics") && !obj.get("syncedLyrics").isJsonNull()) {
                String lrc = obj.get("syncedLyrics").getAsString();
                if (lrc != null && !lrc.isBlank()) {
                    return lrc;
                }
            }
        }

        return null;
    }

    /**
     * Returns LRC lyrics when available. Prefers synced lyrics, but will fall back to plain lyrics
     * (converted into a synthetic timed LRC) so more tracks are considered "having lyrics".
     */
    public String searchLrc(String query, ProgressListener progress) throws Exception {
        String q = query == null ? "" : query.trim();
        if (q.isBlank()) {
            return null;
        }

        String endpoint = "https://lrclib.net/api/search?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8);

        int timeoutSeconds = Math.max(1, plugin.getConfig().getInt("lyrics.timeoutSeconds", 15));
        int retries = Math.max(0, plugin.getConfig().getInt("lyrics.retries", 1));
        int backoffMs = Math.max(0, plugin.getConfig().getInt("lyrics.retryBackoffMs", 250));

        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (Exception e) {
            return null;
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("User-Agent", getUserAgent())
                .GET()
                .build();

        int attempts = 1 + retries;
        HttpResponse<InputStream> res = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            plugin.debug().http("GET " + uri + (attempts > 1 ? " (attempt " + attempt + "/" + attempts + ")" : ""));
            try {
                res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                int code = res.statusCode();
                if (code == 200) {
                    break;
                }
                if (attempt < attempts && isTransientHttp(code)) {
                    sleepBackoff(backoffMs, attempt);
                    continue;
                }
                return null;
            } catch (java.io.IOException e) {
                if (attempt < attempts) {
                    sleepBackoff(backoffMs, attempt);
                    continue;
                }
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }

        if (res == null || res.statusCode() != 200) {
            return null;
        }

        String body;
        try {
            body = readToString(res, progress);
        } catch (Exception e) {
            return null;
        }

        JsonArray arr;
        try {
            arr = JsonParser.parseString(body).getAsJsonArray();
        } catch (Exception e) {
            return null;
        }

        // Prefer synced lyrics
        for (int i = 0; i < arr.size(); i++) {
            JsonObject obj = arr.get(i).getAsJsonObject();
            if (obj == null) {
                continue;
            }
            if (obj.has("syncedLyrics") && !obj.get("syncedLyrics").isJsonNull()) {
                String lrc = obj.get("syncedLyrics").getAsString();
                if (lrc != null && !lrc.isBlank()) {
                    return lrc;
                }
            }
        }

        // Fallback: plain lyrics -> synthetic LRC
        for (int i = 0; i < arr.size(); i++) {
            JsonObject obj = arr.get(i).getAsJsonObject();
            if (obj == null) {
                continue;
            }

            String plain = null;
            if (obj.has("plainLyrics") && !obj.get("plainLyrics").isJsonNull()) {
                plain = obj.get("plainLyrics").getAsString();
            } else if (obj.has("lyrics") && !obj.get("lyrics").isJsonNull()) {
                plain = obj.get("lyrics").getAsString();
            }
            String fake = plainToSyntheticLrc(plain);
            if (fake != null && !fake.isBlank()) {
                return fake;
            }
        }

        return null;
    }

    private String plainToSyntheticLrc(String plain) {
        if (plain == null) {
            return null;
        }
        String text = plain.replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = text.split("\n");
        int lineMs = Math.max(500, plugin.getConfig().getInt("lyrics.unsyncedLineMs", 3500));

        StringBuilder sb = new StringBuilder();
        int emitted = 0;
        for (String raw : lines) {
            if (raw == null) {
                continue;
            }
            String line = raw.trim();
            if (line.isBlank()) {
                continue;
            }
            if (emitted >= 200) {
                break;
            }
            long ms = (long) emitted * (long) lineMs;
            sb.append(formatLrcTimestamp(ms)).append(line).append('\n');
            emitted++;
        }
        return emitted == 0 ? null : sb.toString();
    }

    private static String formatLrcTimestamp(long ms) {
        if (ms < 0) {
            ms = 0;
        }
        long totalSeconds = ms / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        long centis = (ms % 1000L) / 10L;
        return String.format("[%02d:%02d.%02d]", minutes, seconds, centis);
    }

    private static String readToString(HttpResponse<InputStream> res, ProgressListener progress) throws Exception {
        long contentLen = res.headers().firstValueAsLong("Content-Length").orElse(-1L);
        if (progress != null) {
            progress.onProgress(0L, contentLen);
        }

        try (InputStream in = res.body(); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            long total = 0L;
            int r;
            long max = 2L * 1024L * 1024L; // 2MB safety for lrclib JSON
            while ((r = in.read(buf)) != -1) {
                total += r;
                if (total > max) {
                    throw new IllegalStateException("Lyrics response too large");
                }
                baos.write(buf, 0, r);
                if (progress != null) {
                    progress.onProgress(total, contentLen);
                }
            }
            return baos.toString(StandardCharsets.UTF_8);
        }
    }

    private String getUserAgent() {
        String ua = String.valueOf(plugin.getConfig().getString("http.userAgent", "")).trim();
        if (!ua.isBlank()) {
            return ua;
        }
        // Backwards-compatibility
        String legacy = String.valueOf(plugin.getConfig().getString("cache.userAgent", "")).trim();
        return legacy.isBlank() ? "KaraokePlugin/1.0" : legacy;
    }

    private static boolean isTransientHttp(int status) {
        return status == 408 || status == 429 || (status >= 500 && status <= 599);
    }

    private static void sleepBackoff(int baseMs, int attempt) {
        if (baseMs <= 0) {
            return;
        }
        long delay = (long) baseMs * (1L << Math.max(0, attempt - 1));
        delay = Math.min(delay, 2000L);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
