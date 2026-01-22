package me.Luki.karaoke.cache;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import me.Luki.karaoke.Karaoke;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class MediaCache {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type INDEX_TYPE = new TypeToken<Map<String, CachedItem>>() {}.getType();

    private final Karaoke plugin;
    private final HttpClient http;

    private final Path rootDir;
    private final Path audioDir;
    private final Path lyricsDir;
    private final Path indexFile;

    private final Map<String, CachedItem> indexByUrl;
    private final Map<String, CompletableFuture<CachedItem>> inFlight;

    public MediaCache(Karaoke plugin) {
        this.plugin = plugin;
        int timeoutSeconds = Math.max(1, plugin.getConfig().getInt("cache.downloadTimeoutSeconds", 30));
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        this.rootDir = plugin.getDataFolder().toPath().resolve("cache");
        this.audioDir = rootDir.resolve("audio");
        this.lyricsDir = rootDir.resolve("lyrics");
        this.indexFile = rootDir.resolve("index.json");

        this.indexByUrl = new ConcurrentHashMap<>();
        this.inFlight = new ConcurrentHashMap<>();
    }

    public void load() {
        indexByUrl.clear();
        try {
            Files.createDirectories(audioDir);
            Files.createDirectories(lyricsDir);
        } catch (IOException ignored) {
        }

        if (!Files.exists(indexFile)) {
            return;
        }

        try (Reader r = Files.newBufferedReader(indexFile, StandardCharsets.UTF_8)) {
            Map<String, CachedItem> map = GSON.fromJson(r, INDEX_TYPE);
            if (map != null) {
                indexByUrl.putAll(map);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load cache index.json: " + e.getMessage());
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(rootDir);
        } catch (IOException ignored) {
        }

        Path tmp = indexFile.resolveSibling(indexFile.getFileName().toString() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            GSON.toJson(indexByUrl, INDEX_TYPE, w);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to write cache index.json: " + e.getMessage());
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
            return;
        }

        try {
            Files.move(tmp, indexFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            try {
                Files.move(tmp, indexFile, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
            }
        }
    }

    public CachedItem get(String url) {
        if (url == null) {
            return null;
        }
        CachedItem item = indexByUrl.get(url.trim());
        if (item == null) {
            return null;
        }
        item.lastAccessMillis = System.currentTimeMillis();
        return item;
    }

    public void setCachedMeta(String url, String title, String author) {
        if (url == null) {
            return;
        }
        CachedItem item = indexByUrl.computeIfAbsent(url.trim(), CachedItem::new);
        item.cachedTitle = title;
        item.cachedAuthor = author;
        item.lastAccessMillis = System.currentTimeMillis();
        saveSafe();
    }

    public void storeSyncedLyrics(String url, String lrc) {
        if (url == null || lrc == null || lrc.isBlank()) {
            return;
        }

        try {
            Files.createDirectories(lyricsDir);
        } catch (IOException ignored) {
        }

        String fileName = sha256(url) + ".lrc";
        Path out = lyricsDir.resolve(fileName);
        Path tmp = lyricsDir.resolve(fileName + ".part");

        try {
            Files.writeString(tmp, lrc, StandardCharsets.UTF_8);
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
            return;
        }

        CachedItem item = indexByUrl.computeIfAbsent(url.trim(), CachedItem::new);
        item.lyricsRelativePath = rootDir.relativize(out).toString().replace('\\', '/');
        item.lastAccessMillis = System.currentTimeMillis();
        saveSafe();
    }

    public String readSyncedLyrics(String url) {
        CachedItem item = get(url);
        if (item == null) {
            return null;
        }
        Path p = resolveLyricsPath(item);
        if (p == null || !Files.exists(p)) {
            return null;
        }
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isReady(String url) {
        CachedItem item = get(url);
        return item != null && item.status == Status.READY && item.audioRelativePath != null;
    }

    public Path resolveAudioPath(CachedItem item) {
        if (item == null || item.audioRelativePath == null) {
            return null;
        }
        return rootDir.resolve(item.audioRelativePath);
    }

    public Path resolveLyricsPath(CachedItem item) {
        if (item == null || item.lyricsRelativePath == null) {
            return null;
        }
        return rootDir.resolve(item.lyricsRelativePath);
    }

    public CompletableFuture<CachedItem> prefetchAudio(String url) {
        if (!plugin.getConfig().getBoolean("cache.enabled", true)) {
            return CompletableFuture.failedFuture(new IllegalStateException("cache.disabled"));
        }

        String normalized = normalizeUrl(url);
        if (normalized.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("empty url"));
        }

        CachedItem existing = indexByUrl.get(normalized);
        if (existing != null && existing.status == Status.READY && existing.audioRelativePath != null) {
            existing.lastAccessMillis = System.currentTimeMillis();
            return CompletableFuture.completedFuture(existing);
        }

        return inFlight.computeIfAbsent(normalized, key -> CompletableFuture.supplyAsync(() -> {
            CachedItem item = indexByUrl.computeIfAbsent(key, u -> new CachedItem(u));
            item.status = Status.DOWNLOADING;
            item.lastError = null;
            item.lastAccessMillis = System.currentTimeMillis();
            saveSafe();

            try {
                Path downloaded;
                if (isFileUri(key)) {
                    Path local = resolveLocalLibraryFile(fileUriToPath(key));
                    downloaded = importLocalToCache(key, local);
                } else {
                    downloaded = downloadToCache(key);
                }
                long bytes = Files.size(downloaded);

                item.audioRelativePath = rootDir.relativize(downloaded).toString().replace('\\', '/');
                item.audioBytes = bytes;
                item.status = Status.READY;
                item.downloadedAtMillis = System.currentTimeMillis();
                item.lastAccessMillis = System.currentTimeMillis();

                enforceMaxSize();
                saveSafe();

                return item;
            } catch (Exception e) {
                item.status = Status.FAILED;
                item.lastError = e.getMessage();
                saveSafe();
                throw new RuntimeException(e);
            } finally {
                inFlight.remove(key);
            }
        }));
    }

    private Path downloadToCache(String url) throws Exception {
        String extFromUrl = guessExtension(url);

        long maxSingleBytes = Math.max(1L, plugin.getConfig().getLong("cache.maxSingleBytes", 1024L * 1024L * 1024L));

        String sha = sha256(url);
        Path tmp = audioDir.resolve(sha + ".part");

        int timeoutSeconds = Math.max(1, plugin.getConfig().getInt("cache.downloadTimeoutSeconds", 30));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("User-Agent", plugin.getConfig().getString("cache.userAgent", "KaraokePlugin/1.0"))
                .GET()
                .build();

        HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + res.statusCode());
        }

        String contentType = res.headers().firstValue("Content-Type").orElse("");
        String ext = isAllowedExtension(extFromUrl) ? extFromUrl : inferExtensionFromContentType(contentType);
        if (!isAllowedExtension(ext)) {
            throw new IllegalArgumentException("Unsupported file type. Allowed: mp3, ogg (Content-Type=" + (contentType.isBlank() ? "<none>" : contentType) + ")");
        }

        Path out = audioDir.resolve(sha + "." + ext);

        long contentLen = res.headers().firstValueAsLong("Content-Length").orElse(-1L);
        if (contentLen > maxSingleBytes) {
            throw new IllegalStateException("File too large (Content-Length=" + contentLen + ")");
        }

        try (InputStream in = res.body()) {
            Files.createDirectories(audioDir);
            try (var outStream = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[1024 * 64];
                long total = 0L;
                int r;
                while ((r = in.read(buf)) != -1) {
                    total += r;
                    if (total > maxSingleBytes) {
                        throw new IllegalStateException("File too large (exceeded limit)");
                    }
                    outStream.write(buf, 0, r);
                }
            }
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
            throw e;
        }

        return out;
    }

    private Path importLocalToCache(String key, Path localFile) throws Exception {
        String ext = guessExtension(localFile.getFileName().toString());
        if (!isAllowedExtension(ext)) {
            throw new IllegalArgumentException("Unsupported local file type. Allowed: mp3, ogg");
        }

        long maxSingleBytes = Math.max(1L, plugin.getConfig().getLong("cache.maxSingleBytes", 1024L * 1024L * 1024L));
        long size = Files.size(localFile);
        if (size > maxSingleBytes) {
            throw new IllegalStateException("File too large (" + size + " bytes)");
        }

        String sha = sha256(key);
        Path out = audioDir.resolve(sha + "." + ext);
        Path tmp = audioDir.resolve(sha + "." + ext + ".part");

        Files.createDirectories(audioDir);
        try {
            Files.copy(localFile, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
            throw e;
        }
        return out;
    }

    public synchronized void enforceMaxSize() {
        long maxBytes = Math.max(1L, plugin.getConfig().getLong("cache.maxBytes", 10L * 1024L * 1024L * 1024L));

        long total = indexByUrl.values().stream()
                .filter(i -> i != null && i.status == Status.READY)
                .mapToLong(i -> Math.max(0L, i.audioBytes))
                .sum();

        if (total <= maxBytes) {
            return;
        }

        // Evict least-recently-used first
        var entries = indexByUrl.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().status == Status.READY && e.getValue().audioRelativePath != null)
                .sorted((a, b) -> Long.compare(a.getValue().lastAccessMillis, b.getValue().lastAccessMillis))
                .toList();

        for (var e : entries) {
            if (total <= maxBytes) {
                break;
            }
            CachedItem item = e.getValue();
            Path audio = resolveAudioPath(item);
            try {
                if (audio != null && Files.exists(audio)) {
                    long sz = Files.size(audio);
                    Files.deleteIfExists(audio);
                    total -= sz;
                }
            } catch (Exception ex) {
                // ignore and continue
            }
            item.status = Status.EVICTED;
            item.audioRelativePath = null;
            item.audioBytes = 0L;
        }
    }

    private void saveSafe() {
        try {
            save();
        } catch (Exception ignored) {
        }
    }

    private static String normalizeUrl(String url) {
        return url == null ? "" : url.trim();
    }

    private static boolean isFileUri(String url) {
        String u = normalizeUrl(url).toLowerCase(Locale.ROOT);
        return u.startsWith("file:");
    }

    private static Path fileUriToPath(String fileUri) {
        try {
            URI uri = URI.create(fileUri);
            if (!"file".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("not a file URI");
            }
            return Path.of(uri);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid file URI");
        }
    }

    private Path resolveLocalLibraryFile(Path input) {
        boolean enabled = plugin.getConfig().getBoolean("library.enabled", true);
        if (!enabled) {
            throw new IllegalStateException("library.disabled");
        }

        String rootRaw = String.valueOf(plugin.getConfig().getString("library.rootDir", "music")).trim();
        if (rootRaw.isBlank()) {
            rootRaw = "music";
        }
        Path libraryRoot = plugin.getDataFolder().toPath().resolve(rootRaw).toAbsolutePath().normalize();
        try {
            Files.createDirectories(libraryRoot);
        } catch (IOException ignored) {
        }

        boolean allowAbs = plugin.getConfig().getBoolean("library.allowAbsolutePaths", false);
        Path p = input.toAbsolutePath().normalize();
        if (!allowAbs && !p.startsWith(libraryRoot)) {
            throw new IllegalArgumentException("local file is outside library folder");
        }
        if (!Files.exists(p) || !Files.isRegularFile(p)) {
            throw new IllegalArgumentException("local file not found");
        }
        return p;
    }

    private static String guessExtension(String url) {
        String u = normalizeUrl(url);
        int q = u.indexOf('?');
        if (q >= 0) {
            u = u.substring(0, q);
        }
        int hash = u.indexOf('#');
        if (hash >= 0) {
            u = u.substring(0, hash);
        }
        int dot = u.lastIndexOf('.');
        if (dot < 0 || dot == u.length() - 1) {
            return "";
        }
        return u.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean isAllowedExtension(String ext) {
        String e = ext == null ? "" : ext.toLowerCase(Locale.ROOT);
        Set<String> allowed = Set.of("mp3", "ogg");
        return allowed.contains(e);
    }

    private static String inferExtensionFromContentType(String contentType) {
        String ct = Objects.requireNonNullElse(contentType, "").toLowerCase(Locale.ROOT);
        int semi = ct.indexOf(';');
        if (semi >= 0) {
            ct = ct.substring(0, semi).trim();
        }

        return switch (ct) {
            case "audio/mpeg", "audio/mp3", "audio/mpeg3", "audio/x-mpeg" -> "mp3";
            case "audio/ogg", "application/ogg", "audio/opus", "audio/x-ogg" -> "ogg";
            default -> "";
        };
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(Objects.requireNonNullElse(input, "").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public enum Status {
        READY,
        DOWNLOADING,
        FAILED,
        EVICTED
    }

    public static final class CachedItem {
        public final String url;

        public volatile Status status;
        public volatile String audioRelativePath;
        public volatile long audioBytes;

        public volatile String lyricsRelativePath;

        public volatile long downloadedAtMillis;
        public volatile long lastAccessMillis;

        public volatile String cachedTitle;
        public volatile String cachedAuthor;

        public volatile String lastError;

        public CachedItem(String url) {
            this.url = url;
            this.status = Status.DOWNLOADING;
            this.audioRelativePath = null;
            this.audioBytes = 0L;
            this.lyricsRelativePath = null;
            this.downloadedAtMillis = 0L;
            this.lastAccessMillis = System.currentTimeMillis();
            this.cachedTitle = null;
            this.cachedAuthor = null;
            this.lastError = null;
        }
    }
}
