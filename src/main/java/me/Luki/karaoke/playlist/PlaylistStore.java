package me.Luki.karaoke.playlist;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import me.Luki.karaoke.Karaoke;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PlaylistStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, StoredPlaylist>>() {}.getType();

    private final Karaoke plugin;
    private final Path file;

    private final Map<String, StoredPlaylist> playlistsByKey;

    public PlaylistStore(Karaoke plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve("playlists.json");
        this.playlistsByKey = new LinkedHashMap<>();
    }

    public synchronized void load() {
        playlistsByKey.clear();

        try {
            Files.createDirectories(file.getParent());
        } catch (IOException ignored) {
        }

        if (!Files.exists(file)) {
            return;
        }

        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, StoredPlaylist> map = GSON.fromJson(r, MAP_TYPE);
            if (map != null) {
                playlistsByKey.putAll(map);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load playlists.json: " + e.getMessage());
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException ignored) {
        }

        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            GSON.toJson(playlistsByKey, MAP_TYPE, w);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to write playlists.json: " + e.getMessage());
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
            return;
        }

        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
            }
        }
    }

    public synchronized List<String> listNames() {
        return playlistsByKey.values().stream().map(p -> p.name).toList();
    }

    public synchronized Playlist get(String name) {
        String key = key(name);
        StoredPlaylist p = playlistsByKey.get(key);
        if (p == null) {
            return null;
        }
        return new Playlist(p.name, new ArrayList<>(p.entries));
    }

    public synchronized boolean create(String name) {
        String key = key(name);
        if (playlistsByKey.containsKey(key)) {
            return false;
        }
        playlistsByKey.put(key, new StoredPlaylist(name, new ArrayList<>()));
        return true;
    }

    public synchronized boolean delete(String name) {
        return playlistsByKey.remove(key(name)) != null;
    }

    public synchronized boolean add(String playlistName, PlaylistEntry entry) {
        StoredPlaylist p = playlistsByKey.get(key(playlistName));
        if (p == null) {
            return false;
        }

        // Replace by URL first (stable id even if name changes)
        if (entry.url() != null && !entry.url().isBlank()) {
            String urlKey = key(entry.url());
            for (int i = 0; i < p.entries.size(); i++) {
                PlaylistEntry existing = p.entries.get(i);
                if (existing != null && key(existing.url()).equals(urlKey)) {
                    p.entries.set(i, entry);
                    return true;
                }
            }
        }

        // replace by entry name (case-insensitive)
        String entryKey = key(entry.name());
        for (int i = 0; i < p.entries.size(); i++) {
            if (key(p.entries.get(i).name()).equals(entryKey)) {
                p.entries.set(i, entry);
                return true;
            }
        }
        p.entries.add(entry);
        return true;
    }

    public enum SetResult {
        OK,
        NOT_FOUND,
        BAD_ID
    }

    /**
     * Sets or appends an entry at a 1-based slot index.
     * If id == size+1 -> append.
     * If 1 <= id <= size -> replace.
     */
    public synchronized SetResult setAtIndex(String playlistName, int idOneBased, PlaylistEntry entry) {
        StoredPlaylist p = playlistsByKey.get(key(playlistName));
        if (p == null) {
            return SetResult.NOT_FOUND;
        }
        if (idOneBased < 1) {
            return SetResult.BAD_ID;
        }

        int idx = idOneBased - 1;
        if (idx == p.entries.size()) {
            p.entries.add(entry);
            return SetResult.OK;
        }
        if (idx >= 0 && idx < p.entries.size()) {
            p.entries.set(idx, entry);
            return SetResult.OK;
        }
        return SetResult.BAD_ID;
    }

    /** Updates cached metadata for a specific slot (1-based) if it still points to the same URL. */
    public synchronized void upsertAtIndex(String playlistName, int idOneBased, String url, String name, String cachedTitle, String cachedAuthor) {
        StoredPlaylist p = playlistsByKey.get(key(playlistName));
        if (p == null) {
            return;
        }
        int idx = idOneBased - 1;
        if (idx < 0 || idx >= p.entries.size()) {
            return;
        }
        PlaylistEntry existing = p.entries.get(idx);
        if (existing == null) {
            return;
        }
        if (url != null && !url.isBlank() && key(existing.url()).equals(key(url))) {
            String newName = (name == null || name.isBlank()) ? existing.name() : name;
            String canonicalUrl = url.trim();
            p.entries.set(idx, new PlaylistEntry(newName, canonicalUrl, existing.fileUrl(), cachedTitle, cachedAuthor));
        }
    }

    public synchronized void upsertByUrl(String playlistName, String url, String name, String cachedTitle, String cachedAuthor) {
        StoredPlaylist p = playlistsByKey.get(key(playlistName));
        if (p == null) {
            return;
        }
        String urlKey = key(url);
        for (int i = 0; i < p.entries.size(); i++) {
            PlaylistEntry existing = p.entries.get(i);
            if (existing != null && key(existing.url()).equals(urlKey)) {
                String newName = (name == null || name.isBlank()) ? existing.name() : name;
                String canonicalUrl = url == null ? existing.url() : url.trim();
                p.entries.set(i, new PlaylistEntry(newName, canonicalUrl, existing.fileUrl(), cachedTitle, cachedAuthor));
                return;
            }
        }
        String newName = (name == null || name.isBlank()) ? "track" : name;
        p.entries.add(new PlaylistEntry(newName, url == null ? null : url.trim(), null, cachedTitle, cachedAuthor));
    }

    /** Attach/replace the downloadable audio file URL for a specific slot (1-based). */
    public synchronized SetResult setFileUrlAtIndex(String playlistName, int idOneBased, String fileUrl, String optionalName) {
        StoredPlaylist p = playlistsByKey.get(key(playlistName));
        if (p == null) {
            return SetResult.NOT_FOUND;
        }
        if (idOneBased < 1) {
            return SetResult.BAD_ID;
        }
        int idx = idOneBased - 1;
        if (idx < 0 || idx >= p.entries.size()) {
            return SetResult.BAD_ID;
        }
        PlaylistEntry existing = p.entries.get(idx);
        if (existing == null) {
            return SetResult.BAD_ID;
        }
        String newName = (optionalName == null || optionalName.isBlank()) ? existing.name() : optionalName;
        String newFileUrl = (fileUrl == null || fileUrl.isBlank()) ? null : fileUrl.trim();
        p.entries.set(idx, new PlaylistEntry(newName, existing.url(), newFileUrl, existing.cachedTitle(), existing.cachedAuthor()));
        return SetResult.OK;
    }

    /** Returns the entry at a 1-based slot index (or null if missing/out of range). */
    public synchronized PlaylistEntry getAtIndex(String playlistName, int idOneBased) {
        StoredPlaylist p = playlistsByKey.get(key(playlistName));
        if (p == null) {
            return null;
        }
        int idx = idOneBased - 1;
        if (idx < 0 || idx >= p.entries.size()) {
            return null;
        }
        return p.entries.get(idx);
    }

    public synchronized boolean remove(String playlistName, String entryNameOrIndex) {
        StoredPlaylist p = playlistsByKey.get(key(playlistName));
        if (p == null) {
            return false;
        }

        String raw = entryNameOrIndex == null ? "" : entryNameOrIndex.trim();
        if (raw.isEmpty()) {
            return false;
        }

        // index (1-based)
        try {
            int idx = Integer.parseInt(raw);
            int zero = idx - 1;
            if (zero >= 0 && zero < p.entries.size()) {
                p.entries.remove(zero);
                return true;
            }
        } catch (Exception ignored) {
        }

        String entryKey = key(raw);
        for (int i = 0; i < p.entries.size(); i++) {
            if (key(p.entries.get(i).name()).equals(entryKey)) {
                p.entries.remove(i);
                return true;
            }
        }
        return false;
    }

    private static String key(String name) {
        return (name == null ? "" : name.trim().toLowerCase(Locale.ROOT));
    }

    private static final class StoredPlaylist {
        String name;
        List<PlaylistEntry> entries;

        StoredPlaylist(String name, List<PlaylistEntry> entries) {
            this.name = name;
            this.entries = entries;
        }
    }
}
