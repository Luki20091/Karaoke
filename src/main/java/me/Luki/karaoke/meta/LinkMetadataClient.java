package me.Luki.karaoke.meta;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.Luki.karaoke.Karaoke;
import me.Luki.karaoke.util.ProgressListener;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;

public class LinkMetadataClient {

    private final Karaoke plugin;
    private final HttpClient http;
    private final Map<String, CacheEntry> cache;
    private final AtomicLong lastCleanupMillis;

    public LinkMetadataClient(Karaoke plugin) {
        this.plugin = plugin;
        int timeoutSeconds = Math.max(1, plugin.getConfig().getInt("metadata.timeoutSeconds", 10));
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.cache = new ConcurrentHashMap<>();
        this.lastCleanupMillis = new AtomicLong(0L);
    }

    public TrackInfo resolve(String link) throws Exception {
        return resolve(link, null);
    }

    public TrackInfo resolve(String link, ProgressListener progress) throws Exception {
        String url = link.trim();
        if (url.isEmpty()) {
            throw new IllegalArgumentException("Podaj link do utworu.");
        }

        long cacheTtlSeconds = Math.max(0L, plugin.getConfig().getLong("metadata.cacheTtlSeconds", 3600L));
        if (cacheTtlSeconds > 0) {
            CacheEntry cached = cache.get(url);
            if (cached != null && !cached.isExpired(cacheTtlSeconds)) {
                cached.touch();
                plugin.debug().debug(() -> "Metadata cache hit for " + safeShort(url));
                return cached.track;
            }
            if (cached != null && cached.isExpired(cacheTtlSeconds)) {
                cache.remove(url);
            }

            maybeCleanup(cacheTtlSeconds);
        }

        if (looksLikeYouTube(url)) {
            TrackInfo t = resolveYouTubeOEmbed(url, progress);
            putCache(url, t);
            return t;
        }
        if (looksLikeSpotify(url)) {
            TrackInfo t = resolveSpotifyOEmbed(url, progress);
            putCache(url, t);
            return t;
        }

        // Fallback: we don't know how to resolve this link yet
        TrackInfo t = new TrackInfo(url, null, "link");
        putCache(url, t);
        return t;
    }

    private TrackInfo resolveYouTubeOEmbed(String url, ProgressListener progress) throws Exception {
        String endpoint = "https://www.youtube.com/oembed?format=json&url=" +
                URLEncoder.encode(url, StandardCharsets.UTF_8);

        JsonObject obj = getJson(endpoint, progress);
        String title = getString(obj, "title");
        String author = getString(obj, "author_name");
        return new TrackInfo(title != null ? title : url, author, "youtube");
    }

    private TrackInfo resolveSpotifyOEmbed(String url, ProgressListener progress) throws Exception {
        String endpoint = "https://open.spotify.com/oembed?url=" +
                URLEncoder.encode(url, StandardCharsets.UTF_8);

        JsonObject obj = getJson(endpoint, progress);
        String title = getString(obj, "title");
        return new TrackInfo(title != null ? title : url, "Spotify", "spotify");
    }

    private JsonObject getJson(String endpoint, ProgressListener progress) throws Exception {
        int timeoutSeconds = Math.max(1, plugin.getConfig().getInt("metadata.timeoutSeconds", 10));
        int retries = Math.max(0, plugin.getConfig().getInt("metadata.retries", 1));
        int backoffMs = Math.max(0, plugin.getConfig().getInt("metadata.retryBackoffMs", 250));

        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (Exception e) {
            throw new IllegalArgumentException("Nieprawidłowy link/endpoint dla metadanych.");
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("User-Agent", getUserAgent())
                .GET()
                .build();

        int attempts = 1 + retries;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            plugin.debug().http("GET " + uri + (attempts > 1 ? " (attempt " + attempt + "/" + attempts + ")" : ""));
            try {
                HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                int code = res.statusCode();
                if (code == 200) {
                    try {
                        String body = readToString(res, progress);
                        return JsonParser.parseString(body).getAsJsonObject();
                    } catch (Exception e) {
                        plugin.debug().warn("Metadata JSON parse failed for " + uri, e);
                        throw new IllegalStateException("Nie udało się odczytać metadanych (zły format odpowiedzi).");
                    }
                }

                plugin.debug().http("HTTP " + code + " from " + uri);
                if (attempt < attempts && isTransientHttp(code)) {
                    sleepBackoff(backoffMs, attempt);
                    continue;
                }
                throw new IllegalStateException("Nie udało się pobrać metadanych (HTTP " + code + ")");
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

        throw new IllegalStateException("Nie udało się pobrać metadanych.");
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
            long max = 1024L * 1024L; // 1MB safety for oEmbed JSON
            while ((r = in.read(buf)) != -1) {
                total += r;
                if (total > max) {
                    throw new IllegalStateException("Metadata response too large");
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

    private void putCache(String url, TrackInfo track) {
        long cacheTtlSeconds = Math.max(0L, plugin.getConfig().getLong("metadata.cacheTtlSeconds", 3600L));
        if (cacheTtlSeconds <= 0) {
            return;
        }
        cache.put(url, new CacheEntry(track));
        maybeCleanup(cacheTtlSeconds);
    }

    private void maybeCleanup(long cacheTtlSeconds) {
        long cleanupIntervalSeconds = Math.max(1L, plugin.getConfig().getLong("metadata.cacheCleanupIntervalSeconds", 120L));
        long now = System.currentTimeMillis();
        long last = lastCleanupMillis.get();
        if ((now - last) < cleanupIntervalSeconds * 1000L) {
            return;
        }
        if (!lastCleanupMillis.compareAndSet(last, now)) {
            return;
        }

        // 1) Remove expired entries
        for (Map.Entry<String, CacheEntry> entry : cache.entrySet()) {
            CacheEntry ce = entry.getValue();
            if (ce == null) {
                cache.remove(entry.getKey());
                continue;
            }
            if (ce.isExpired(cacheTtlSeconds)) {
                cache.remove(entry.getKey());
            }
        }

        // 2) Enforce max size (0 = unlimited)
        int maxEntries = Math.max(0, plugin.getConfig().getInt("metadata.cacheMaxEntries", 500));
        if (maxEntries <= 0) {
            return;
        }

        int size = cache.size();
        if (size <= maxEntries) {
            return;
        }

        // Remove least-recently-used first (by lastAccessMillis)
        List<Map.Entry<String, CacheEntry>> entries = new ArrayList<>(cache.entrySet());
        entries.sort(Comparator.comparingLong(e -> e.getValue() != null ? e.getValue().lastAccessMillis : 0L));

        int toRemove = size - maxEntries;
        for (int i = 0; i < entries.size() && toRemove > 0; i++) {
            String key = entries.get(i).getKey();
            if (key == null) {
                continue;
            }
            cache.remove(key);
            toRemove--;
        }
    }

    private static String safeShort(String value) {
        if (value == null) {
            return "<null>";
        }
        String v = value.trim();
        if (v.length() <= 80) {
            return v;
        }
        return v.substring(0, 77) + "...";
    }

    private static final class CacheEntry {
        private final TrackInfo track;
        private final long createdMillis;
        private volatile long lastAccessMillis;

        private CacheEntry(TrackInfo track) {
            this.track = track;
            this.createdMillis = System.currentTimeMillis();
            this.lastAccessMillis = this.createdMillis;
        }

        private void touch() {
            this.lastAccessMillis = System.currentTimeMillis();
        }

        private boolean isExpired(long ttlSeconds) {
            long ttlMs = Math.max(0L, ttlSeconds) * 1000L;
            return ttlMs > 0 && (System.currentTimeMillis() - createdMillis) > ttlMs;
        }
    }

    private static String getString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        try {
            return obj.get(key).getAsString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean looksLikeYouTube(String url) {
        String u = url.toLowerCase();
        return u.contains("youtube.com/") || u.contains("youtu.be/");
    }

    private static boolean looksLikeSpotify(String url) {
        String u = url.toLowerCase();
        return u.contains("open.spotify.com/");
    }
}
