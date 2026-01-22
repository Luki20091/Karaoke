package me.Luki.karaoke.command;

import me.Luki.karaoke.Karaoke;
import me.Luki.karaoke.cache.MediaCache;
import me.Luki.karaoke.lyrics.LrclibClient;
import me.Luki.karaoke.meta.LinkMetadataClient;
import me.Luki.karaoke.playlist.Playlist;
import me.Luki.karaoke.playlist.PlaylistEntry;
import me.Luki.karaoke.playlist.PlaylistStore;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;

public class PlaylistCommand implements CommandExecutor {

    private final Karaoke plugin;
    private final PlaylistStore store;
    private final MediaCache cache;
    private final LinkMetadataClient metadata;
    private final LrclibClient lrclib;

    public PlaylistCommand(Karaoke plugin, PlaylistStore store) {
        this.plugin = plugin;
        this.store = store;
        this.cache = plugin.mediaCache();
        this.metadata = new LinkMetadataClient(plugin);
        this.lrclib = new LrclibClient(plugin);
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
            plugin.messages().send(sender, "playlistUsage", "&7Użycie: /" + label + " <create|delete|list|add|addfile|remove|show> ...");
            return true;
        }

        // Support both syntaxes:
        // 1) /playlist <sub> ... (legacy)
        // 2) /playlist <playlistName> <sub> ... (requested)
        ParsedCommand parsedCmd = parseCommandShape(args);
        if (parsedCmd == null) {
            plugin.messages().send(sender, "playlistUsage", "&7Użycie: /" + label + " <create|delete|list|add|addfile|remove|show> ...");
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

                boolean ok = store.add(playlist, new PlaylistEntry(songName, url, null, null, null));
                if (!ok) {
                    plugin.messages().send(sender, "playlistNotFound", "&cNie znaleziono playlisty.");
                    return true;
                }
                store.save();
                plugin.messages().send(sender, "playlistAdded", "&aDodano &f{song}&a do &f{pl}&a.", "song", songName, "pl", playlist);

                // Fetch metadata + timed lyrics (LRC) in background.
                plugin.messages().send(sender, "playlistPrefetchStart", "&7Pobieram nazwę/wykonawcę + tekst w tle…");

                String initialName = songName;
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        var track = metadata.resolve(url);
                        String cachedTitle = track.title();
                        String cachedAuthor = track.author();
                        cache.setCachedMeta(url, cachedTitle, cachedAuthor);

                        String nameToUse = initialName;
                        if (parsed.name.isBlank() && cachedTitle != null && !cachedTitle.isBlank()) {
                            nameToUse = cachedTitle;
                        }

                        String lrc = null;
                        try {
                            String query = (cachedAuthor != null && !cachedAuthor.isBlank()) ? (cachedTitle + " " + cachedAuthor) : cachedTitle;
                            lrc = lrclib.searchSyncedLrc(query);
                        } catch (Exception ignored) {
                        }
                        if (lrc != null && !lrc.isBlank()) {
                            cache.storeSyncedLyrics(url, lrc);
                        }

                        String finalNameToUse = nameToUse;
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            store.upsertByUrl(playlist, url, finalNameToUse, cachedTitle, cachedAuthor);
                            store.save();
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
                plugin.messages().send(sender, "playlistUsage", "&7Użycie: /" + label + " <create|delete|list|add|addfile|setfile|remove|show> ...");
                return true;
            }
        }
    }

    public Collection<String> suggest(@NotNull CommandSender sender, @NotNull String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("karaoke.playlist")) {
            return out;
        }

        // When the user types "/playlist " and hits tab, Paper may pass args.length==0.
        if (args.length == 0) {
            out.addAll(List.of("show", "create", "delete", "list", "add", "addfile", "setfile", "remove", "play"));
            return out;
        }

        if (args.length == 1) {
            String prefix = args[0] == null ? "" : args[0].toLowerCase(Locale.ROOT);
            for (String sub : List.of("show", "create", "delete", "list", "add", "addfile", "setfile", "remove", "play")) {
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
            if (List.of("delete", "list", "add", "addfile", "setfile", "remove", "show", "play").contains(sub)) {
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
                for (String cmd : List.of("show", "list", "add", "addfile", "setfile", "remove", "play")) {
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

        plugin.messages().send(sender, "playlistPrefetchStart", "&7Pobieram plik audio w tle…");
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
        plugin.messages().send(sender, "playlistPrefetchStart", "&7Buforuję audio + tekst w tle…");

        String finalSongName = songName;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                cache.prefetchAudio(url).join();

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
        return List.of("show", "create", "delete", "list", "add", "addfile", "setfile", "remove", "play").contains(v);
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
