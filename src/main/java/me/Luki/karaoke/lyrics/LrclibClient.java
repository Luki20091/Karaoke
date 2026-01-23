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

public final class LrclibClient {

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
