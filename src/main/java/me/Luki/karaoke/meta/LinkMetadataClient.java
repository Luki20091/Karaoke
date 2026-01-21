package me.Luki.karaoke.meta;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.Luki.karaoke.Karaoke;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
            TrackInfo t = resolveYouTubeOEmbed(url);
            putCache(url, t);
            return t;
        }
        if (looksLikeSpotify(url)) {
            TrackInfo t = resolveSpotifyOEmbed(url);
            putCache(url, t);
            return t;
        }

        // Fallback: we don't know how to resolve this link yet
        TrackInfo t = new TrackInfo(url, null, "link");
        putCache(url, t);
        return t;
    }

    private TrackInfo resolveYouTubeOEmbed(String url) throws Exception {
        String endpoint = "https://www.youtube.com/oembed?format=json&url=" +
                URLEncoder.encode(url, StandardCharsets.UTF_8);

        JsonObject obj = getJson(endpoint);
        String title = getString(obj, "title");
        String author = getString(obj, "author_name");
        return new TrackInfo(title != null ? title : url, author, "youtube");
    }

    private TrackInfo resolveSpotifyOEmbed(String url) throws Exception {
        String endpoint = "https://open.spotify.com/oembed?url=" +
                URLEncoder.encode(url, StandardCharsets.UTF_8);

        JsonObject obj = getJson(endpoint);
        String title = getString(obj, "title");
        return new TrackInfo(title != null ? title : url, "Spotify", "spotify");
    }

    private JsonObject getJson(String endpoint) throws Exception {
        int timeoutSeconds = Math.max(1, plugin.getConfig().getInt("metadata.timeoutSeconds", 10));

        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (Exception e) {
            throw new IllegalArgumentException("Nieprawidłowy link/endpoint dla metadanych.");
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("User-Agent", "KaraokePlugin/1.0")
                .GET()
                .build();

        plugin.debug().http("GET " + uri);
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() != 200) {
            plugin.debug().http("HTTP " + res.statusCode() + " from " + uri);
            throw new IllegalStateException("Nie udało się pobrać metadanych (HTTP " + res.statusCode() + ")");
        }
        try {
            return JsonParser.parseString(res.body()).getAsJsonObject();
        } catch (Exception e) {
            plugin.debug().warn("Metadata JSON parse failed for " + uri, e);
            throw new IllegalStateException("Nie udało się odczytać metadanych (zły format odpowiedzi).");
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
