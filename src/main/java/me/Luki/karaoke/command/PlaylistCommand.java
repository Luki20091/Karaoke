package me.Luki.karaoke.command;

import me.Luki.karaoke.Karaoke;
import me.Luki.karaoke.cache.MediaCache;
import me.Luki.karaoke.lyrics.LrclibClient;
import me.Luki.karaoke.meta.LinkMetadataClient;
import me.Luki.karaoke.playlist.Playlist;
import me.Luki.karaoke.playlist.PlaylistEntry;
import me.Luki.karaoke.playlist.PlaylistStore;
import me.Luki.karaoke.util.ActionBarProgress;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class PlaylistCommand implements CommandExecutor {

    private final Karaoke plugin;
    private final PlaylistStore store;
    private final MediaCache cache;
    private final LinkMetadataClient metadata;
    private final LrclibClient lrclib;
    private final HttpClient http;

    public PlaylistCommand(Karaoke plugin, PlaylistStore store) {
        this.plugin = plugin;
        this.store = store;
        this.cache = plugin.mediaCache();
        this.metadata = new LinkMetadataClient(plugin);
        this.lrclib = new LrclibClient(plugin);
        int timeoutSeconds = Math.max(5, plugin.getConfig().getInt("metadata.timeoutSeconds", 10));
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        return execute(sender, label, args);
    }

    public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("karaoke.playlist")) {
            plugin.messages().send(sender, "noPermission", "&cBrak uprawnień.");
            return true;
        }

        if (args.length < 1) {
            plugin.messages().send(sender, "playlistUsage", "&7Użycie: /" + label + " <create|delete|list|add|addfile|setfile|remove|show|prefetch|import|export> ...");
            return true;
        }

        // Support both syntaxes:
        // 1) /playlist <sub> ... (legacy)
        // 2) /playlist <playlistName> <sub> ... (requested)
        ParsedCommand parsedCmd = parseCommandShape(args);
        if (parsedCmd == null) {
            plugin.messages().send(sender, "playlistUsage", "&7Użycie: /" + label + " <create|delete|list|add|addfile|setfile|remove|show|prefetch|import|export> ...");
            return true;
        }

        String sub = parsedCmd.sub;
        switch (sub) {
            case "show" -> {
                if (parsedCmd.playlist != null && !parsedCmd.playlist.isBlank()) {
                    return listPlaylist(sender, parsedCmd.playlist.trim());
                }
                if (parsedCmd.tail.length >= 1) {
                    return listPlaylist(sender, parsedCmd.tail[0].trim());
                }
                return listPlaylists(sender);
            }
            case "create" -> {
                if (parsedCmd.tail.length < 1) {
                    plugin.messages().send(sender, "playlistCreateUsage", "&7Użycie: /" + label + " create <nazwa>");
                    return true;
                }
                String name = parsedCmd.tail[0].trim();
                if (name.isEmpty()) {
                    plugin.messages().send(sender, "playlistCreateUsage", "&7Użycie: /" + label + " create <nazwa>");
                    return true;
                }
                boolean ok = store.create(name);
                if (!ok) {
                    plugin.messages().send(sender, "playlistExists", "&cTaka playlista już istnieje.");
                    return true;
                }
                store.save();
                plugin.messages().send(sender, "playlistCreated", "&aUtworzono playlistę &f{name}&a.", "name", name);
                return true;
            }
            case "delete" -> {
                if (parsedCmd.tail.length < 1) {
                    plugin.messages().send(sender, "playlistDeleteUsage", "&7Użycie: /" + label + " delete <nazwa>");
                    return true;
                }
                String name = parsedCmd.tail[0].trim();
                boolean ok = store.delete(name);
                if (!ok) {
                    plugin.messages().send(sender, "playlistNotFound", "&cNie znaleziono playlisty.");
                    return true;
                }
                store.save();
                plugin.messages().send(sender, "playlistDeleted", "&aUsunięto playlistę &f{name}&a.", "name", name);
                return true;
            }
            case "list" -> {
                if (parsedCmd.playlist != null && !parsedCmd.playlist.isBlank()) {
                    return listPlaylist(sender, parsedCmd.playlist.trim());
                }
                if (parsedCmd.tail.length < 1) {
                    plugin.messages().send(sender, "playlistListUsage", "&7Użycie: /" + label + " <playlist> list");
                    return true;
                }
                return listPlaylist(sender, parsedCmd.tail[0].trim());
            }
            case "add" -> {
                if (parsedCmd.tail.length < 2) {
                    plugin.messages().send(sender, "playlistAddUsage",
                            "&7Użycie: /" + label + " add <playlist> <url> [nazwa...] &8(lub)&7 /" + label + " add <playlist> [nazwa...] <url>");
                    return true;
                }

                String playlist = parsedCmd.playlist != null ? parsedCmd.playlist.trim() : parsedCmd.tail[0].trim();
                if (playlist.isEmpty()) {
                    plugin.messages().send(sender, "playlistAddUsage",
                            "&7Użycie: /" + label + " add <playlist> <url> [nazwa...] &8(lub)&7 /" + label + " add <playlist> [nazwa...] <url>");
                    return true;
                }

                // Accept both orders:
                // - /playlist add <pl> <url> [name...]
                // - /playlist add <pl> [name...] <url>
                String[] tail = parsedCmd.playlist != null ? parsedCmd.tail : Arrays.copyOfRange(parsedCmd.tail, 1, parsedCmd.tail.length);
                ParsedAdd parsed = parseAddArgs(tail);
                if (parsed == null || parsed.url.isBlank()) {
                    plugin.messages().send(sender, "playlistAddUsage",
                            "&7Użycie: /" + label + " add <playlist> <url> [nazwa...] &8(lub)&7 /" + label + " add <playlist> [nazwa...] <url>");
                    return true;
                }

                String url = parsed.url;
                String songName = parsed.name;
                if (songName.isBlank()) {
                    songName = "track-" + shortId(url);
                }

                // Validate + cache metadata/lyrics on add (async), then add only if lyrics exist.
                plugin.messages().send(sender, "playlistPrefetchMetadataStart", "&7Pobieram informacje z YouTube…");

                String initialName = songName;
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        var track = metadata.resolve(url);
                        String cachedTitle = track.title();
                        String cachedAuthor = track.author();
                        cache.setCachedMeta(url, cachedTitle, cachedAuthor);

                        plugin.getServer().getScheduler().runTask(plugin, () ->
                                plugin.messages().send(sender, "playlistPrefetchLyricsStart", "&7Pobieram tekst z API…")
                        );

                        String nameToUse = initialName;
                        if (parsed.name.isBlank() && cachedTitle != null && !cachedTitle.isBlank()) {
                            nameToUse = cachedTitle;
                        }

                        String lrc = null;
                        try {
                            String query = (cachedAuthor != null && !cachedAuthor.isBlank()) ? (cachedTitle + " " + cachedAuthor) : cachedTitle;
                            lrc = lrclib.searchLrc(query, null);
                        } catch (Exception ignored) {
                        }
                        if (lrc == null || lrc.isBlank()) {
                            plugin.getServer().getScheduler().runTask(plugin, () ->
                                    plugin.messages().send(sender, "noLyricsAvailable", "&cBrak tekstu do tej piosenki w API."));
                            return;
                        }

                        cache.storeSyncedLyrics(url, lrc);

                        String finalNameToUse = nameToUse;
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            boolean ok = store.add(playlist, new PlaylistEntry(finalNameToUse, url, null, cachedTitle, cachedAuthor));
                            if (!ok) {
                                plugin.messages().send(sender, "playlistNotFound", "&cNie znaleziono playlisty.");
                                return;
                            }
                            store.upsertByUrl(playlist, url, finalNameToUse, cachedTitle, cachedAuthor);
                            store.save();
                            plugin.messages().send(sender, "playlistAdded", "&aDodano &f{song}&a do &f{pl}&a.", "song", finalNameToUse, "pl", playlist);
                            plugin.messages().send(sender, "playlistPrefetchDone",
                                    "&aZbuforowano: &f{title}&7 - &f{author}",
                                    "title", cachedTitle != null ? cachedTitle : finalNameToUse,
                                    "author", cachedAuthor != null ? cachedAuthor : "?");
                        });

                    } catch (Exception e) {
                        plugin.getServer().getScheduler().runTask(plugin, () ->
                                plugin.messages().send(sender, "playlistPrefetchFail", "&cBuforowanie nieudane: {error}", "error", String.valueOf(e.getMessage()))
                        );
                    }
                });
                return true;
            }
            case "prefetch", "prefetchlyrics", "warmup" -> {
                String playlist;
                if (parsedCmd.playlist != null && !parsedCmd.playlist.isBlank()) {
                    playlist = parsedCmd.playlist.trim();
                } else {
                    if (parsedCmd.tail.length < 1) {
                        plugin.messages().send(sender, "playlistPrefetchUsage", "&7Użycie: /" + label + " <playlist> prefetch");
                        return true;
                    }
                    playlist = parsedCmd.tail[0].trim();
                }
                if (playlist.isBlank()) {
                    plugin.messages().send(sender, "playlistPrefetchUsage", "&7Użycie: /" + label + " <playlist> prefetch");
                    return true;
                }
                return prefetchPlaylist(sender, playlist);
            }
            case "import" -> {
                String playlist;
                String csvUrl;

                if (parsedCmd.playlist != null && !parsedCmd.playlist.isBlank()) {
                    playlist = parsedCmd.playlist.trim();
                    if (parsedCmd.tail.length < 1) {
                        plugin.messages().send(sender, "playlistImportUsage", "&7Użycie: /" + label + " <playlist> import <csv_url>");
                        return true;
                    }
                    csvUrl = parsedCmd.tail[0].trim();
                } else {
                    if (parsedCmd.tail.length < 2) {
                        plugin.messages().send(sender, "playlistImportUsage", "&7Użycie: /" + label + " import <playlist> <csv_url>");
                        return true;
                    }
                    playlist = parsedCmd.tail[0].trim();
                    csvUrl = parsedCmd.tail[1].trim();
                }

                if (playlist.isBlank() || csvUrl.isBlank()) {
                    plugin.messages().send(sender, "playlistImportUsage", "&7Użycie: /" + label + " <playlist> import <csv_url>");
                    return true;
                }

                return importPlaylistFromCsv(sender, playlist, csvUrl);
            }
            case "export" -> {
                String playlist;
                if (parsedCmd.playlist != null && !parsedCmd.playlist.isBlank()) {
                    playlist = parsedCmd.playlist.trim();
                } else {
                    if (parsedCmd.tail.length < 1) {
                        plugin.messages().send(sender, "playlistExportUsage", "&7Użycie: /" + label + " export <playlist>");
                        return true;
                    }
                    playlist = parsedCmd.tail[0].trim();
                }

                if (playlist.isBlank()) {
                    plugin.messages().send(sender, "playlistExportUsage", "&7Użycie: /" + label + " export <playlist>");
                    return true;
                }

                return exportPlaylistToCsv(sender, playlist);
            }
            case "addfile", "setfile" -> {
                // Requested: /playlist <playlist> addfile <id> <url_to_mp3>
                // Also keep old: /playlist addfile <playlist> <file.mp3> [name...]
                if (parsedCmd.playlist != null && !parsedCmd.playlist.isBlank()) {
                    return addFileByIdAndUrl(sender, label, parsedCmd.playlist.trim(), parsedCmd.tail);
                }
                return addLocalLibraryFile(sender, label, parsedCmd.tail);
            }
            case "remove" -> {
                String playlist;
                String key;
                if (parsedCmd.playlist != null && !parsedCmd.playlist.isBlank()) {
                    playlist = parsedCmd.playlist.trim();
                    if (parsedCmd.tail.length < 1) {
                        plugin.messages().send(sender, "playlistRemoveUsage", "&7Użycie: /" + label + " <playlist> remove <id>");
                        return true;
                    }
                    key = parsedCmd.tail[0].trim();
                } else {
                    if (parsedCmd.tail.length < 2) {
                        plugin.messages().send(sender, "playlistRemoveUsage", "&7Użycie: /" + label + " remove <playlist> <id>");
                        return true;
                    }
                    playlist = parsedCmd.tail[0].trim();
                    key = parsedCmd.tail[1].trim();
                }
                boolean ok = store.remove(playlist, key);
                if (!ok) {
                    plugin.messages().send(sender, "playlistRemoveFail", "&cNie udało się usunąć (zła playlista lub pozycja)." );
                    return true;
                }
                store.save();
                plugin.messages().send(sender, "playlistRemoved", "&aUsunięto pozycję z &f{pl}&a.", "pl", playlist);
                return true;
            }
            case "play" -> {
                if (!(sender instanceof org.bukkit.entity.Player player)) {
                    plugin.messages().send(sender, "onlyPlayers", "Ta komenda jest tylko dla graczy.");
                    return true;
                }
                String pl;
                String colorRaw;
                if (parsedCmd.playlist != null && !parsedCmd.playlist.isBlank()) {
                    pl = parsedCmd.playlist.trim();
                    if (parsedCmd.tail.length < 1) {
                        plugin.messages().send(sender, "playlistPlayUsage", "&7Użycie: /" + label + " <playlist> play <kolor>");
                        return true;
                    }
                    colorRaw = parsedCmd.tail[0].trim();
                } else {
                    if (parsedCmd.tail.length < 2) {
                        plugin.messages().send(sender, "playlistPlayUsage", "&7Użycie: /" + label + " play <playlist> <kolor>");
                        return true;
                    }
                    pl = parsedCmd.tail[0].trim();
                    colorRaw = parsedCmd.tail[1].trim();
                }
                try {
                    me.Luki.karaoke.service.KaraokeTextColor.fromPolish(colorRaw);
                } catch (IllegalArgumentException e) {
                    plugin.messages().send(sender, "invalidColor", "&cNieprawidłowy kolor. Użyj: czerwony/zielony/niebieski");
                    return true;
                }
                plugin.getServer().getScheduler().runTask(plugin, () -> plugin.getServer().dispatchCommand(player, "karaoke play " + pl + " " + colorRaw));
                return true;
            }
            default -> {
                plugin.messages().send(sender, "playlistUsage", "&7Użycie: /" + label + " <create|delete|list|add|addfile|setfile|remove|show|prefetch|import|export> ...");
                return true;
            }
        }
    }

    private boolean exportPlaylistToCsv(CommandSender sender, String playlistName) {
        Playlist p = store.get(playlistName);
        if (p == null) {
            plugin.messages().send(sender, "playlistNotFound", "&cNie znaleziono playlisty.");
            return true;
        }

        Path outDir = plugin.getDataFolder().toPath().resolve("exported");
        try {
            Files.createDirectories(outDir);
        } catch (Exception e) {
            plugin.messages().send(sender, "playlistExportFail", "&cNie udało się zapisać CSV: {error}", "error", "mkdir");
            return true;
        }

        String base = safeFileBase(p.name());
        long ts = Instant.now().toEpochMilli();
        Path out = outDir.resolve(base + "-" + ts + ".csv");

        StringBuilder sb = new StringBuilder();
        sb.append("id,yt_link,file_link\n");
        List<PlaylistEntry> entries = p.entries();
        for (int i = 0; i < entries.size(); i++) {
            PlaylistEntry e = entries.get(i);
            int id = i + 1;
            String yt = e != null ? String.valueOf(e.url()) : "";
            String file = (e != null && e.fileUrl() != null) ? e.fileUrl() : "";
            sb.append(id)
                    .append(',')
                    .append(csvField(yt))
                    .append(',')
                    .append(csvField(file))
                    .append('\n');
        }

        try {
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            plugin.messages().send(sender, "playlistExportFail", "&cNie udało się zapisać CSV: {error}", "error", String.valueOf(e.getMessage()));
            return true;
        }

        plugin.messages().send(sender, "playlistExportDone", "&aZapisano CSV: &f{file} &7(poziomów: {count})",
                "file", out.toAbsolutePath().normalize().toString(),
                "count", String.valueOf(entries.size()));
        return true;
    }

    private static String csvField(String raw) {
        String v = raw == null ? "" : raw;
        boolean needsQuotes = v.contains(",") || v.contains("\n") || v.contains("\r") || v.contains("\"") || v.contains(";");
        if (!needsQuotes) {
            return v;
        }
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    private static String safeFileBase(String name) {
        String v = name == null ? "playlist" : name.trim();
        if (v.isBlank()) {
            v = "playlist";
        }
        // keep it filesystem-friendly
        v = v.replaceAll("[^a-zA-Z0-9._-]+", "_");
        if (v.length() > 40) {
            v = v.substring(0, 40);
        }
        if (v.isBlank()) {
            v = "playlist";
        }
        return v;
    }

    private boolean importPlaylistFromCsv(CommandSender sender, String playlistName, String csvUrlRaw) {
        if (cache == null) {
            plugin.messages().send(sender, "cacheDisabled", "&cCache jest wyłączony.");
            return true;
        }

        // Auto-fix common Dropbox links: dl=0 -> dl=1
        String csvUrl = csvUrlRaw == null ? "" : csvUrlRaw.trim();
        if (csvUrl.contains("dropbox.com") && csvUrl.contains("dl=0")) {
            csvUrl = csvUrl.replace("dl=0", "dl=1");
        }

        if (!(csvUrl.startsWith("http://") || csvUrl.startsWith("https://"))) {
            plugin.messages().send(sender, "playlistImportBadUrl", "&cTo nie wygląda na URL: &f{url}", "url", csvUrl);
            return true;
        }

        // Ensure playlist exists
        if (store.get(playlistName) == null) {
            boolean created = store.create(playlistName);
            if (created) {
                store.save();
            }
        }

        plugin.messages().send(sender, "playlistImportStart", "&7Importuję CSV do playlisty &f{name}&7…", "name", playlistName);

        Player player = (sender instanceof Player p) ? p : null;
        String finalCsvUrl = csvUrl;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            ActionBarProgress bar = ActionBarProgress.start(plugin, player, "Import");
            int ok = 0;
            int fail = 0;
            int processed = 0;
            int total = 0;

            try {
                CsvDownload dl = downloadCsv(finalCsvUrl, playlistName);
                if (dl == null || dl.csvText == null || dl.csvText.isBlank()) {
                    String err = (dl != null && dl.error != null) ? String.valueOf(dl.error).trim() : "";
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (!err.isBlank()) {
                            plugin.messages().send(sender, "playlistImportDownloadFail", "&cNie udało się pobrać CSV: {error}", "error", err);
                        } else {
                            plugin.messages().send(sender, "playlistImportEmpty", "&cPusty plik CSV albo nie udało się go pobrać.");
                        }
                    });
                    return;
                }

                if (dl.savedCopyPath != null) {
                    String savedPath = dl.savedCopyPath.toAbsolutePath().normalize().toString();
                    plugin.getServer().getScheduler().runTask(plugin, () ->
                            plugin.messages().send(sender, "playlistImportSavedCopy", "&7Zapisano kopię CSV: &f{file}", "file", savedPath));
                }

                List<CsvRow> rows;
                try {
                    rows = parseImportCsv(dl.csvText);
                } catch (Exception e) {
                    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    plugin.getServer().getScheduler().runTask(plugin, () ->
                            plugin.messages().send(sender, "playlistImportBadCsv", "&cNie udało się zparsować CSV: {error}", "error", msg));
                    return;
                }

                total = rows.size();
                if (bar != null) {
                    bar.setStage("Import 0/" + total);
                    bar.update(0L, total);
                }

                for (int i = 0; i < rows.size(); i++) {
                    CsvRow r = rows.get(i);
                    int idx1 = i + 1;
                    processed = idx1;

                    String yt = r.ytLink == null ? "" : r.ytLink.trim();
                    String file = r.fileLink == null ? "" : r.fileLink.trim();
                    PlaylistEntry existingEntry = store.getAtIndex(playlistName, r.id);

                    String ytToUse = !yt.isBlank() ? yt : (existingEntry != null ? existingEntry.url() : "");
                    String fileToUse = !file.isBlank() ? file : (existingEntry != null ? existingEntry.fileUrl() : null);
                    String sourceUrl = !ytToUse.isBlank() ? ytToUse : (fileToUse == null ? "" : fileToUse);
                    if (sourceUrl == null || sourceUrl.isBlank()) {
                        fail++;
                        if (bar != null) {
                            bar.setStage("Import " + processed + "/" + total + " (ok=" + ok + ")");
                            bar.update(processed, total);
                        }
                        continue;
                    }

                    try {
                        if (bar != null) {
                            bar.setStage("Import " + processed + "/" + total + " (ok=" + ok + ")");
                            bar.update(processed - 1L, total);
                        }

                        // Insert/replace at requested 1-based id.
                        String entryName = existingEntry != null ? existingEntry.name() : ("track-" + r.id);

                        boolean urlChanged = existingEntry != null
                                && existingEntry.url() != null
                                && !existingEntry.url().trim().equals(sourceUrl.trim());
                        if (urlChanged) {
                            entryName = "track-" + r.id;
                        }

                        String cachedTitleFromExisting = (existingEntry == null || urlChanged) ? null : existingEntry.cachedTitle();
                        String cachedAuthorFromExisting = (existingEntry == null || urlChanged) ? null : existingEntry.cachedAuthor();

                        PlaylistEntry entry = new PlaylistEntry(
                                entryName,
                                sourceUrl.trim(),
                                (fileToUse == null || fileToUse.isBlank()) ? null : fileToUse.trim(),
                                cachedTitleFromExisting,
                                cachedAuthorFromExisting
                        );
                        PlaylistStore.SetResult setRes = store.setAtIndex(playlistName, r.id, entry);
                        if (setRes != PlaylistStore.SetResult.OK) {
                            throw new IllegalArgumentException("Nieprawidłowe id w CSV (musi być 1..N bez przerw): " + r.id);
                        }

                        // Metadata (only when we have an actual link like YouTube/Spotify)
                        String cachedTitle = null;
                        String cachedAuthor = null;
                        boolean missingLyrics = false;
                        if (!ytToUse.isBlank()) {
                            if (bar != null) {
                                bar.setStage("YouTube " + processed + "/" + total);
                            }
                            var track = metadata.resolve(ytToUse, null);
                            cachedTitle = track.title();
                            cachedAuthor = track.author();
                            cache.setCachedMeta(ytToUse, cachedTitle, cachedAuthor);

                            // Lyrics
                            String lrcExisting = null;
                            try {
                                lrcExisting = cache.readSyncedLyrics(ytToUse);
                            } catch (Exception ignored) {
                            }
                            if (lrcExisting == null || lrcExisting.isBlank()) {
                                if (bar != null) {
                                    bar.setStage("Tekst " + processed + "/" + total);
                                }
                                String query = (cachedAuthor != null && !cachedAuthor.isBlank()) ? (cachedTitle + " " + cachedAuthor) : cachedTitle;
                                String lrc = lrclib.searchLrc(query, null);
                                if (lrc != null && !lrc.isBlank()) {
                                    cache.storeSyncedLyrics(ytToUse, lrc);
                                } else {
                                    missingLyrics = true;
                                    int id = r.id;
                                    String titleToSend = cachedTitle != null && !cachedTitle.isBlank() ? cachedTitle : entryName;
                                    String ytToSend = ytToUse;
                                    plugin.getServer().getScheduler().runTask(plugin, () ->
                                            plugin.messages().send(sender, "playlistImportNoLyrics", "&eBrak tekstu w API (id={id}): &f{title}",
                                                    "id", String.valueOf(id),
                                                    "title", titleToSend,
                                                    "url", ytToSend));
                                }
                            }

                            // Persist title/author back into playlists.json for this slot
                            String nameToUse = cachedTitle != null && !cachedTitle.isBlank() ? cachedTitle : entryName;
                            store.upsertAtIndex(playlistName, r.id, ytToUse, nameToUse, cachedTitle, cachedAuthor);
                        }

                        // Audio
                        if (!file.isBlank()) {
                            if (bar != null) {
                                bar.setStage("Audio " + processed + "/" + total);
                            }
                            cache.prefetchAudio(file).join();
                        }

                        if (missingLyrics) {
                            fail++;
                        } else {
                            ok++;
                        }
                        if (bar != null) {
                            bar.setStage("Import " + processed + "/" + total + " (ok=" + ok + ")");
                            bar.update(processed, total);
                        }

                        if ((idx1 % 5) == 0) {
                            store.save();
                        }
                    } catch (Exception ex) {
                        fail++;
                        String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                        int id = r.id;
                        int processedNow = processed;
                        int totalNow = total;
                        plugin.getServer().getScheduler().runTask(plugin, () ->
                                plugin.messages().send(sender, "playlistImportItemFail", "&cImport nieudany ({i}/{n}) id={id}: {error}",
                                        "i", String.valueOf(processedNow),
                                        "n", String.valueOf(totalNow),
                                        "id", String.valueOf(id),
                                        "error", msg));
                        if (bar != null) {
                            bar.setStage("Import " + processed + "/" + total + " (ok=" + ok + ")");
                            bar.update(processed, total);
                        }
                    }
                }
            } finally {
                try {
                    store.save();
                } catch (Exception ignored) {
                }
                if (bar != null) {
                    bar.close();
                }

                int okFinal = ok;
                int failFinal = fail;
                int totalFinal = total;
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        plugin.messages().send(sender, "playlistImportDone", "&aImport zakończony: ok={ok}/{total} fail={fail}",
                                "ok", String.valueOf(okFinal),
                                "total", String.valueOf(totalFinal),
                                "fail", String.valueOf(failFinal)));
            }
        });

        return true;
    }

    private record CsvDownload(String csvText, Path savedCopyPath, String error) {}

    private CsvDownload downloadCsv(String csvUrl, String playlistName) {
        int timeoutSeconds = Math.max(5, plugin.getConfig().getInt("metadata.timeoutSeconds", 10));
        long maxBytes = 5L * 1024L * 1024L; // 5MB safety

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(csvUrl))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("User-Agent", getUserAgent())
                .GET()
                .build();

        try {
            HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (res.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + res.statusCode());
            }

            byte[] bytes;
            try (InputStream in = res.body(); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                long total = 0L;
                int r;
                while ((r = in.read(buf)) != -1) {
                    total += r;
                    if (total > maxBytes) {
                        throw new IllegalStateException("CSV too large");
                    }
                    baos.write(buf, 0, r);
                }
                bytes = baos.toByteArray();
            }

            String text = new String(bytes, StandardCharsets.UTF_8);

            Path saved = null;
            try {
                Path importedDir = plugin.getDataFolder().toPath().resolve("imported");
                Files.createDirectories(importedDir);
                String safeName = (playlistName == null ? "playlist" : playlistName.trim()).replaceAll("[^a-zA-Z0-9._-]+", "_");
                String fileName = safeName + "-" + Instant.now().toEpochMilli() + ".csv";
                saved = importedDir.resolve(fileName);
                Files.write(saved, bytes);
            } catch (Exception ignored) {
            }

            return new CsvDownload(text, saved, null);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new CsvDownload(null, null, msg);
        }
    }

    private static List<CsvRow> parseImportCsv(String csvText) {
        String raw = csvText == null ? "" : csvText;
        raw = raw.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = raw.split("\n");

        int headerIdx = -1;
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (l == null) continue;
            String t = l.trim();
            if (!t.isEmpty()) {
                headerIdx = i;
                break;
            }
        }
        if (headerIdx < 0) {
            throw new IllegalArgumentException("CSV jest pusty");
        }

        String headerLine = stripBom(lines[headerIdx].trim());
        char sep = detectSeparator(headerLine);
        List<String> headers = splitCsvLine(headerLine, sep);
        Map<String, Integer> headerMap = new HashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            headerMap.put(normalizeHeader(headers.get(i)), i);
        }

        Integer idCol = headerMap.get("id");
        Integer ytCol = headerMap.get("yt_link");
        Integer fileCol = headerMap.get("file_link");
        if (idCol == null || ytCol == null || fileCol == null) {
            throw new IllegalArgumentException("Brak nagłówków: id, yt_link, file_link");
        }

        List<CsvRow> out = new ArrayList<>();
        java.util.Set<Integer> seenIds = new java.util.HashSet<>();
        java.util.List<Integer> duplicateIds = new java.util.ArrayList<>();
        int minId = Integer.MAX_VALUE;
        int maxId = Integer.MIN_VALUE;
        for (int i = headerIdx + 1; i < lines.length; i++) {
            String line = lines[i];
            if (line == null) {
                continue;
            }
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }

            List<String> cols = splitCsvLine(line, sep);
            String idRaw = getAt(cols, idCol);
            String yt = getAt(cols, ytCol);
            String file = getAt(cols, fileCol);

            int id;
            try {
                id = Integer.parseInt(idRaw.trim());
            } catch (Exception e) {
                throw new IllegalArgumentException("Nieprawidłowe id w wierszu " + (i + 1));
            }

            if (id < 1) {
                throw new IllegalArgumentException("Błędne indexowanie: id musi być >= 1 (wiersz " + (i + 1) + ")");
            }
            if (!seenIds.add(id)) {
                duplicateIds.add(id);
            }
            minId = Math.min(minId, id);
            maxId = Math.max(maxId, id);

            out.add(new CsvRow(id, yt, file));
        }

        if (out.isEmpty()) {
            throw new IllegalArgumentException("CSV nie zawiera wierszy");
        }

        if (!duplicateIds.isEmpty()) {
            duplicateIds.sort(Integer::compareTo);
            String dup = duplicateIds.stream().distinct().limit(20).map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("?");
            throw new IllegalArgumentException("Błędne indexowanie: zduplikowane id: " + dup);
        }

        if (minId != 1) {
            throw new IllegalArgumentException("Błędne indexowanie: minimalne id=" + minId + " (musi być 1)");
        }

        // Must be exactly 1..N without gaps
        if (seenIds.size() != maxId) {
            java.util.List<Integer> missing = new java.util.ArrayList<>();
            for (int id = 1; id <= maxId; id++) {
                if (!seenIds.contains(id)) {
                    missing.add(id);
                    if (missing.size() >= 20) {
                        break;
                    }
                }
            }
            String miss = missing.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("?");
            throw new IllegalArgumentException("Błędne indexowanie: brakuje id: " + miss + " (oczekiwane 1.." + maxId + ")");
        }

        // Sort by id so imports happen in order (required for append semantics)
        out.sort((a, b) -> Integer.compare(a.id, b.id));
        return out;
    }

    private record CsvRow(int id, String ytLink, String fileLink) {}

    private static String stripBom(String s) {
        if (s == null || s.isEmpty()) return "";
        if (s.charAt(0) == '\uFEFF') return s.substring(1);
        return s;
    }

    private static String normalizeHeader(String s) {
        String t = s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
        t = t.replace("\"", "");
        return t;
    }

    private static char detectSeparator(String headerLine) {
        int commas = 0;
        int semis = 0;
        for (int i = 0; i < headerLine.length(); i++) {
            char c = headerLine.charAt(i);
            if (c == ',') commas++;
            if (c == ';') semis++;
        }
        return semis > commas ? ';' : ',';
    }

    private static List<String> splitCsvLine(String line, char sep) {
        List<String> out = new ArrayList<>();
        if (line == null) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    // Escaped quote ""
                    cur.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
                continue;
            }

            if (!inQuotes && c == sep) {
                out.add(cur.toString().trim());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        out.add(cur.toString().trim());
        return out;
    }

    private static String getAt(List<String> cols, int idx) {
        if (cols == null || idx < 0 || idx >= cols.size()) {
            return "";
        }
        return cols.get(idx);
    }

    private String getUserAgent() {
        String ua = String.valueOf(plugin.getConfig().getString("http.userAgent", "")).trim();
        if (!ua.isBlank()) {
            return ua;
        }
        String legacy = String.valueOf(plugin.getConfig().getString("cache.userAgent", "")).trim();
        return legacy.isBlank() ? "KaraokePlugin/1.0" : legacy;
    }

    private boolean prefetchPlaylist(CommandSender sender, String playlistName) {
        Playlist pl = store.get(playlistName);
        if (pl == null) {
            plugin.messages().send(sender, "playlistNotFound", "&cNie znaleziono playlisty.");
            return true;
        }
        if (pl.entries().isEmpty()) {
            plugin.messages().send(sender, "playlistEmpty", "&7Playlista &f{name}&7 jest pusta.", "name", pl.name());
            return true;
        }
        if (cache == null) {
            plugin.messages().send(sender, "cacheDisabled", "&cCache jest wyłączony.");
            return true;
        }

        plugin.messages().send(sender, "playlistPrefetchAllStart", "&7Prefetch playlisty &f{name}&7: meta + tekst + audio…", "name", pl.name());

        Player player = (sender instanceof Player p) ? p : null;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            ActionBarProgress bar = ActionBarProgress.start(plugin, player, "Prefetch");
            int totalEntries = pl.entries().size();
            int ok = 0;
            int fail = 0;

            try {
                for (int i = 0; i < pl.entries().size(); i++) {
                    PlaylistEntry e = pl.entries().get(i);
                    if (e == null || e.url() == null || e.url().isBlank()) {
                        continue;
                    }

                    String sourceUrl = e.url().trim();
                    String display = (e.cachedTitle() != null && !e.cachedTitle().isBlank()) ? e.cachedTitle() : e.name();
                    int idx1 = i + 1;

                    try {
                        if (bar != null) {
                            bar.setStage("YouTube " + idx1 + "/" + totalEntries);
                        }
                        var track = metadata.resolve(sourceUrl, bar != null ? bar.asListener() : null);
                        cache.setCachedMeta(sourceUrl, track.title(), track.author());

                        String lrcExisting = null;
                        try {
                            lrcExisting = cache.readSyncedLyrics(sourceUrl);
                        } catch (Exception ignored) {
                        }

                        if (lrcExisting == null || lrcExisting.isBlank()) {
                            if (bar != null) {
                                bar.setStage("Tekst " + idx1 + "/" + totalEntries);
                            }
                            String query = (track.author() != null && !track.author().isBlank()) ? (track.title() + " " + track.author()) : track.title();
                            String lrc = lrclib.searchSyncedLrc(query, bar != null ? bar.asListener() : null);
                            if (lrc != null && !lrc.isBlank()) {
                                cache.storeSyncedLyrics(sourceUrl, lrc);
                            }
                        }

                        // If fileUrl is present, warm the audio cache too.
                        if (e.fileUrl() != null && !e.fileUrl().isBlank()) {
                            if (bar != null) {
                                bar.setStage("Audio " + idx1 + "/" + totalEntries);
                            }
                            cache.prefetchAudio(e.fileUrl().trim(), bar != null ? bar.asListener() : null).join();
                        }

                        // Persist title/author back into playlists.json
                        store.upsertByUrl(playlistName, sourceUrl, display, track.title(), track.author());
                        ok++;
                    } catch (Exception ex) {
                        fail++;
                        String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                        plugin.getServer().getScheduler().runTask(plugin, () ->
                                plugin.messages().send(sender, "playlistPrefetchItemFail", "&cPrefetch nieudany ({i}/{n}): &f{name}&7 ({error})",
                                        "i", String.valueOf(idx1),
                                        "n", String.valueOf(totalEntries),
                                        "name", display,
                                        "error", msg)
                        );
                    }
                }
            } finally {
                if (bar != null) {
                    bar.close();
                }
            }

            final int okFinal = ok;
            final int failFinal = fail;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                store.save();
                plugin.messages().send(sender, "playlistPrefetchAllDone", "&aPrefetch gotowy: ok={ok} fail={fail}",
                        "ok", String.valueOf(okFinal),
                        "fail", String.valueOf(failFinal));
            });
        });
        return true;
    }

    public Collection<String> suggest(@NotNull CommandSender sender, @NotNull String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("karaoke.playlist")) {
            return out;
        }

        // When the user types "/playlist " and hits tab, Paper may pass args.length==0.
        if (args.length == 0) {
            out.addAll(List.of("show", "create", "delete", "list", "add", "addfile", "setfile", "remove", "play", "prefetch", "import", "export"));
            return out;
        }

        if (args.length == 1) {
            String prefix = args[0] == null ? "" : args[0].toLowerCase(Locale.ROOT);
            for (String sub : List.of("show", "create", "delete", "list", "add", "addfile", "setfile", "remove", "play", "prefetch", "import", "export")) {
                if (sub.startsWith(prefix)) {
                    out.add(sub);
                }
            }
            // Also suggest playlist names in position 1 for playlist-first syntax
            for (String name : store.listNames()) {
                if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    out.add(name);
                }
            }
            return out;
        }

        if (args.length == 2) {
            String sub = args[0] == null ? "" : args[0].toLowerCase(Locale.ROOT);
            if (List.of("delete", "list", "add", "addfile", "setfile", "remove", "show", "play", "import", "prefetch", "export").contains(sub)) {
                String prefix = args[1] == null ? "" : args[1].toLowerCase(Locale.ROOT);
                for (String name : store.listNames()) {
                    if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        out.add(name);
                    }
                }
            }
            // playlist-first: /playlist <playlist> <sub>
            if (!isSubcommand(sub)) {
                String prefix = args[1] == null ? "" : args[1].toLowerCase(Locale.ROOT);
                for (String cmd : List.of("show", "list", "add", "addfile", "setfile", "remove", "play", "prefetch", "import", "export")) {
                    if (cmd.startsWith(prefix)) {
                        out.add(cmd);
                    }
                }
            }
            return out;
        }

        if (args.length == 3 && "play".equalsIgnoreCase(args[0])) {
            String prefix = args[2] == null ? "" : args[2].toLowerCase(Locale.ROOT);
            for (String c : List.of("czerwony", "zielony", "niebieski")) {
                if (c.startsWith(prefix)) {
                    out.add(c);
                }
            }
            return out;
        }

        return out;
    }

    private boolean listPlaylists(CommandSender sender) {
        List<String> names = store.listNames();
        if (names.isEmpty()) {
            plugin.messages().send(sender, "playlistNone", "&7Brak playlist.");
            return true;
        }
        plugin.messages().send(sender, "playlistShow", "&aPlaylisty: &f" + String.join(", ", names));
        return true;
    }

    private static TitleAuthor guessTitleAuthor(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isBlank()) {
            return new TitleAuthor("", "");
        }
        // Common filename format: "Artist - Title"
        int idx = n.indexOf(" - ");
        if (idx > 0 && idx < n.length() - 3) {
            String a = n.substring(0, idx).trim();
            String t = n.substring(idx + 3).trim();
            if (!t.isBlank()) {
                return new TitleAuthor(t, a);
            }
        }
        return new TitleAuthor(n, "");
    }

    private boolean listPlaylist(CommandSender sender, String name) {
        if (name == null || name.isBlank()) {
            plugin.messages().send(sender, "playlistListUsage", "&7Użycie: /playlist <playlist> list");
            return true;
        }
        Playlist p = store.get(name);
        if (p == null) {
            plugin.messages().send(sender, "playlistNotFound", "&cNie znaleziono playlisty.");
            return true;
        }
        if (p.entries().isEmpty()) {
            plugin.messages().send(sender, "playlistEmpty", "&7Playlista &f{name}&7 jest pusta.", "name", p.name());
            return true;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("&aPlaylista &f").append(p.name()).append("&a (pozycje: ").append(p.entries().size()).append(")&7\n");
        for (int i = 0; i < p.entries().size(); i++) {
            int id = i + 1;
            PlaylistEntry e = p.entries().get(i);
            String title = (e.cachedTitle() != null && !e.cachedTitle().isBlank()) ? e.cachedTitle() : e.name();
            String author = e.cachedAuthor() != null && !e.cachedAuthor().isBlank() ? e.cachedAuthor() : "?";
            String url = e.url();
            boolean attached = e.fileUrl() != null && !e.fileUrl().isBlank();
            sb.append("&f").append(id).append("&7) ")
                    .append("&a").append(title)
                    .append("&7 - &f").append(author)
                    .append("&7 | &b").append(url)
                    .append("&7 | attached: &f").append(attached);
            if (i < p.entries().size() - 1) {
                sb.append("&7\n");
            }
        }
        plugin.messages().send(sender, "playlistList", sb.toString(), "name", p.name());
        return true;
    }

    private boolean addFileByIdAndUrl(CommandSender sender, String label, String playlist, String[] tail) {
        if (tail.length < 2) {
            plugin.messages().send(sender, "playlistAddFileUsage",
                    "&7Użycie: /" + label + " <playlist> addfile <id> <url_do_mp3/ogg> [nazwa...]");
            return true;
        }

        int id;
        try {
            id = Integer.parseInt(tail[0].trim());
        } catch (Exception e) {
            plugin.messages().send(sender, "playlistAddFileUsage",
                    "&7Użycie: /" + label + " <playlist> addfile <id> <url_do_mp3/ogg> [nazwa...]");
            return true;
        }
        String url = tail[1].trim();
        String songName = tail.length > 2 ? String.join(" ", Arrays.copyOfRange(tail, 2, tail.length)).trim() : "";
        String optionalName = songName.isBlank() ? null : songName;

        String displayName = songName;
        try {
            Playlist p = store.get(playlist);
            if ((displayName == null || displayName.isBlank()) && p != null) {
                int idx = id - 1;
                if (idx >= 0 && idx < p.entries().size()) {
                    PlaylistEntry existing = p.entries().get(idx);
                    if (existing != null) {
                        displayName = (existing.cachedTitle() != null && !existing.cachedTitle().isBlank()) ? existing.cachedTitle() : existing.name();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (displayName == null || displayName.isBlank()) {
            displayName = "id=" + id;
        }

        // Attach audio file URL without overwriting the source (e.g. YouTube) URL.
        PlaylistStore.SetResult r = store.setFileUrlAtIndex(playlist, id, url, optionalName);
        if (r == PlaylistStore.SetResult.NOT_FOUND) {
            plugin.messages().send(sender, "playlistNotFound", "&cNie znaleziono playlisty.");
            return true;
        }
        if (r == PlaylistStore.SetResult.BAD_ID) {
            plugin.messages().send(sender, "playlistAddFileBadId", "&cNieprawidłowe id. Użyj istniejącego id albo następnego (N+1).");
            return true;
        }

        store.save();
        plugin.messages().send(sender, "playlistAdded", "&aUstawiono &f{song}&a w &f{pl}&a (id={id}).",
            "song", displayName, "pl", playlist, "id", String.valueOf(id));

        plugin.messages().send(sender, "playlistPrefetchAudioStart", "&7Pobieram plik audio…");
        int finalId = id;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                cache.prefetchAudio(url).join();

                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    store.save();
                    plugin.messages().send(sender, "playlistPrefetchDone",
                            "&aZbuforowano audio dla id=&f{id}",
                            "id", String.valueOf(finalId));
                });
            } catch (Exception e) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        plugin.messages().send(sender, "playlistPrefetchFail", "&cBuforowanie nieudane: {error}", "error", String.valueOf(e.getMessage()))
                );
            }
        });
        return true;
    }

    private boolean addLocalLibraryFile(CommandSender sender, String label, String[] args) {
        // Old syntax: /playlist addfile <playlist> <plik.mp3|plik.ogg> [nazwa...]
        if (args.length < 2) {
            plugin.messages().send(sender, "playlistAddFileUsage",
                    "&7Użycie: /" + label + " addfile <playlist> <plik.mp3|plik.ogg> [nazwa...] &8(plik z folderu library.rootDir)");
            return true;
        }

        String playlist = args[0].trim();
        if (playlist.isEmpty()) {
            plugin.messages().send(sender, "playlistAddFileUsage",
                    "&7Użycie: /" + label + " addfile <playlist> <plik.mp3|plik.ogg> [nazwa...] &8(plik z folderu library.rootDir)");
            return true;
        }

        // If user used legacy syntax but passed an URL, guide them to the id+url form.
        if (args.length >= 3) {
            String maybeId = args[1].trim();
            String maybeUrl = args[2].trim();
            if (looksLikeUrl(maybeUrl)) {
                try {
                    int id = Integer.parseInt(maybeId);
                    return addFileByIdAndUrl(sender, label, playlist, new String[] { String.valueOf(id), maybeUrl });
                } catch (Exception ignored) {
                }
            }
        }
        if (looksLikeUrl(args[1])) {
            plugin.messages().send(sender, "playlistAddFileUsage",
                    "&7Użycie: /" + label + " <playlist> addfile <id> <url_do_mp3/ogg> [nazwa...] &8(lub)&7 /" + label + " addfile <playlist> <plik.mp3|plik.ogg>");
            return true;
        }

        String fileArg = args[1].trim();
        String songName = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)).trim() : "";

        Path libraryRoot = plugin.getDataFolder().toPath()
                .resolve(String.valueOf(plugin.getConfig().getString("library.rootDir", "music")).trim())
                .toAbsolutePath()
                .normalize();
        try {
            Files.createDirectories(libraryRoot);
        } catch (Exception ignored) {
        }

        Path file = Path.of(fileArg);
        if (!file.isAbsolute()) {
            file = libraryRoot.resolve(fileArg).normalize();
        } else {
            file = file.toAbsolutePath().normalize();
        }

        boolean allowAbs = plugin.getConfig().getBoolean("library.allowAbsolutePaths", false);
        if (!allowAbs && !file.startsWith(libraryRoot)) {
            plugin.messages().send(sender, "playlistAddFileOutsideLibrary",
                    "&cTen plik jest poza folderem biblioteki: &f{dir}",
                    "dir", libraryRoot.toString());
            return true;
        }

        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            plugin.messages().send(sender, "playlistAddFileNotFound", "&cNie znaleziono pliku: &f{file}", "file", file.toString());
            return true;
        }

        String url = file.toUri().toString();
        if (songName.isBlank()) {
            String fn = file.getFileName().toString();
            int dot = fn.lastIndexOf('.');
            songName = dot > 0 ? fn.substring(0, dot) : fn;
        }

        boolean ok = store.add(playlist, new PlaylistEntry(songName, url, url, null, null));
        if (!ok) {
            plugin.messages().send(sender, "playlistNotFound", "&cNie znaleziono playlisty.");
            return true;
        }
        store.save();
        plugin.messages().send(sender, "playlistAdded", "&aDodano &f{song}&a do &f{pl}&a.", "song", songName, "pl", playlist);
        plugin.messages().send(sender, "playlistPrefetchAudioStart", "&7Pobieram plik audio…");

        String finalSongName = songName;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                cache.prefetchAudio(url).join();

                plugin.getServer().getScheduler().runTask(plugin, () ->
                    plugin.messages().send(sender, "playlistPrefetchLyricsStart", "&7Pobieram tekst z API…")
                );

                // For local files we can't use oEmbed metadata; use provided name.
                TitleAuthor ta = guessTitleAuthor(finalSongName);
                cache.setCachedMeta(url, ta.title, ta.author);

                String lrc = null;
                try {
                    String query = (ta.author != null && !ta.author.isBlank()) ? (ta.title + " " + ta.author) : ta.title;
                    lrc = lrclib.searchSyncedLrc(query);
                } catch (Exception ignored) {
                }
                if (lrc != null && !lrc.isBlank()) {
                    cache.storeSyncedLyrics(url, lrc);
                }

                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    store.upsertByUrl(playlist, url, finalSongName, ta.title, ta.author);
                    store.save();
                    plugin.messages().send(sender, "playlistPrefetchDone",
                            "&aZbuforowano plik: &f{title}&7 - &f{author}",
                            "title", ta.title != null ? ta.title : finalSongName,
                            "author", ta.author != null ? ta.author : "?");
                });
            } catch (Exception e) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        plugin.messages().send(sender, "playlistPrefetchFail", "&cBuforowanie nieudane: {error}", "error", String.valueOf(e.getMessage()))
                );
            }
        });

        return true;
    }

    private static ParsedCommand parseCommandShape(String[] args) {
        if (args == null || args.length == 0) {
            return null;
        }

        String first = args[0] == null ? "" : args[0].trim().toLowerCase(Locale.ROOT);
        if (isSubcommand(first)) {
            // legacy: /playlist <sub> ...
            String[] tail = Arrays.copyOfRange(args, 1, args.length);
            return new ParsedCommand(null, first, tail);
        }

        if (args.length >= 2) {
            String second = args[1] == null ? "" : args[1].trim().toLowerCase(Locale.ROOT);
            if (isSubcommand(second)) {
                // playlist-first: /playlist <playlist> <sub> ...
                String playlist = args[0].trim();
                String[] tail = Arrays.copyOfRange(args, 2, args.length);
                return new ParsedCommand(playlist, second, tail);
            }
        }

        return null;
    }

    private static boolean isSubcommand(String s) {
        if (s == null) {
            return false;
        }
        String v = s.trim().toLowerCase(Locale.ROOT);
        return List.of("show", "create", "delete", "list", "add", "addfile", "setfile", "remove", "play", "prefetch", "prefetchlyrics", "warmup", "import", "export").contains(v);
    }

    private static ParsedAdd parseAddArgs(String[] tail) {
        if (tail == null || tail.length == 0) {
            return null;
        }

        // Find URL token (first or last usually).
        int urlIndex = -1;
        for (int i = 0; i < tail.length; i++) {
            if (looksLikeUrl(tail[i])) {
                urlIndex = i;
                break;
            }
        }
        if (urlIndex == -1) {
            // Maybe URL is last but doesn't start with http(s) (unlikely) - reject.
            return null;
        }

        String url = tail[urlIndex].trim();
        String name = "";
        if (tail.length > 1) {
            String[] nameParts = new String[tail.length - 1];
            int n = 0;
            for (int i = 0; i < tail.length; i++) {
                if (i == urlIndex) {
                    continue;
                }
                nameParts[n++] = tail[i];
            }
            name = String.join(" ", Arrays.copyOf(nameParts, n)).trim();
        }
        return new ParsedAdd(url, name);
    }

    private static boolean looksLikeUrl(String s) {
        if (s == null) {
            return false;
        }
        String v = s.trim().toLowerCase(Locale.ROOT);
        return v.startsWith("http://") || v.startsWith("https://");
    }

    private static String shortId(String url) {
        return UUID.nameUUIDFromBytes(String.valueOf(url).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString()
                .substring(0, 8);
    }

    private record ParsedAdd(String url, String name) {
    }

    private record TitleAuthor(String title, String author) {
    }

    private record ParsedCommand(String playlist, String sub, String[] tail) {
    }
}
