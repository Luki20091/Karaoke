package me.Luki.karaoke.service;

import me.Luki.karaoke.Karaoke;
import me.Luki.karaoke.lyrics.LyricsClient;
import me.Luki.karaoke.lyrics.PlaceholderTimedLyrics;
import me.Luki.karaoke.lyrics.TimedLyrics;
import me.Luki.karaoke.meta.LinkMetadataClient;
import me.Luki.karaoke.meta.TrackInfo;
import me.Luki.karaoke.playlist.PlayerQueue;
import me.Luki.karaoke.playlist.Playlist;
import me.Luki.karaoke.playlist.PlaylistEntry;
import me.Luki.karaoke.playlist.PlaylistStore;
import me.Luki.karaoke.cache.MediaCache;
import me.Luki.karaoke.lyrics.LrcParser;
import me.Luki.karaoke.lyrics.LrcTimedLyrics;
import me.Luki.karaoke.util.ActionBarProgress;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class KaraokeService {

    private final Karaoke plugin;
    private final PlaylistStore playlistStore;
    private final Map<UUID, KaraokeSession> sessions;
    private final Map<UUID, StartReservation> reservations;
    private final Map<UUID, PlayerQueue> queues;

    private final LinkMetadataClient metadataClient;
    private final LyricsClient lyricsClient;

    public KaraokeService(Karaoke plugin, PlaylistStore playlistStore) {
        this.plugin = plugin;
        this.playlistStore = playlistStore;
        this.sessions = new ConcurrentHashMap<>();
        this.reservations = new ConcurrentHashMap<>();
        this.queues = new ConcurrentHashMap<>();
        this.metadataClient = new LinkMetadataClient(plugin);
        this.lyricsClient = new LyricsClient(plugin);
    }

    public Karaoke getPlugin() {
        return plugin;
    }

    public PlaylistStore getPlaylistStore() {
        return playlistStore;
    }

    public void start(Player player, String link, KaraokeTextColor color) {
        startInternal(player, link, color, true);
    }

    public void play(Player player, String target, KaraokeTextColor color) {
        if (player == null) {
            return;
        }
        if (target == null || target.trim().isEmpty()) {
            plugin.messages().send(player, "provideLink", "&cPodaj link do utworu.");
            return;
        }

        // Prefer playlist by exact name if it exists.
        Playlist pl = playlistStore != null ? playlistStore.get(target) : null;
        if (pl != null) {
            if (pl.entries().isEmpty()) {
                plugin.messages().send(player, "playlistEmpty", "&7Playlista &f{name}&7 jest pusta.", "name", pl.name());
                return;
            }

            stop(player, true);
            PlayerQueue q = new PlayerQueue(pl.entries(), color, player.getLocation());
            queues.put(player.getUniqueId(), q);
            PlaylistEntry first = q.current();
            if (first == null) {
                queues.remove(player.getUniqueId());
                plugin.messages().send(player, "playlistEmpty", "&7Playlista &f{name}&7 jest pusta.", "name", pl.name());
                return;
            }

            plugin.messages().send(player, "playlistPlaying", "&aGram playlistę &f{name}&a.", "name", pl.name());
            startFromCacheOrPrefetch(player, q.origin(), first, color);
            return;
        }

        // Otherwise, treat as a direct link.
        startInternal(player, target, color, true);
    }

    public void skip(Player player) {
        if (player == null) {
            return;
        }
        UUID id = player.getUniqueId();
        PlayerQueue q = queues.get(id);
        if (q == null) {
            stop(player, true);
            plugin.messages().send(player, "stopped", "&aKaraoke zatrzymane.");
            return;
        }

        stop(player, false);
        PlaylistEntry next = q.next();
        if (next == null) {
            queues.remove(id);
            plugin.messages().send(player, "playlistEnded", "&7Koniec playlisty.");
            return;
        }

        startFromCacheOrPrefetch(player, q.origin(), next, q.color());
    }

    private void startFromCacheOrPrefetch(Player player, Location origin, PlaylistEntry entry, KaraokeTextColor color) {
        if (player == null || entry == null) {
            return;
        }

        MediaCache cache = plugin.mediaCache();
        if (cache == null) {
            plugin.messages().send(player, "cacheDisabled", "&cCache jest wyłączony lub niedostępny.");
            return;
        }

        String sourceUrl = entry.url();
        String audioUrl = entry.fileUrl();

        if (audioUrl == null || audioUrl.isBlank()) {
            plugin.messages().send(player, "playlistMissingFile", "&cTen utwór nie ma podpiętego pliku audio. Użyj: &f/playlist <pl> setfile <id> <url_do_mp3/ogg>");
            return;
        }

        MediaCache.CachedItem item = cache.get(audioUrl);

        if (item == null || item.status != MediaCache.Status.READY || item.audioRelativePath == null) {
            plugin.messages().send(player, "loadingAudio", "&7Pobieram plik audio…");
            ActionBarProgress bar = ActionBarProgress.start(plugin, player, "Audio");
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    if (bar != null) {
                        bar.setStage("Audio");
                    }
                    cache.prefetchAudio(audioUrl, bar != null ? bar.asListener() : null).join();
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) {
                            plugin.messages().send(player, "loadedAudio", "&aPlik audio gotowy.");
                        }
                    });
                } catch (Exception ignored) {
                } finally {
                    if (bar != null) {
                        bar.close();
                    }
                }
            });
            return;
        }

        // Build TrackInfo from cached metadata if available (fast, no network)
        MediaCache.CachedItem sourceItem = cache.get(sourceUrl);
        String title = sourceItem != null && sourceItem.cachedTitle != null && !sourceItem.cachedTitle.isBlank()
                ? sourceItem.cachedTitle
                : (entry.cachedTitle() != null ? entry.cachedTitle() : entry.name());
        String author = sourceItem != null && sourceItem.cachedAuthor != null && !sourceItem.cachedAuthor.isBlank()
                ? sourceItem.cachedAuthor
                : entry.cachedAuthor();
        TrackInfo track = new TrackInfo(title != null ? title : entry.name(), author, "cache");

        // Lyrics: prefer cached LRC from disk; otherwise use placeholder and fetch asynchronously.
        TimedLyrics lyrics = null;
        boolean needsAsyncLyrics = false;
        try {
            String lrc = cache.readSyncedLyrics(sourceUrl);
            if (lrc != null && !lrc.isBlank()) {
                var parsed = LrcParser.parse(lrc);
                if (!parsed.isEmpty()) {
                    lyrics = new LrcTimedLyrics(parsed);
                }
            }
        } catch (Exception ignored) {
        }
        if (lyrics == null) {
            lyrics = new PlaceholderTimedLyrics(track);
            needsAsyncLyrics = true;
        }

        KaraokeSession session = startCachedSession(player, origin, audioUrl, track, lyrics, color);
        if (needsAsyncLyrics && session != null) {
            UUID playerId = player.getUniqueId();
            plugin.messages().send(player, "loadingLyrics", "&7Pobieram tekst…");
            ActionBarProgress bar = ActionBarProgress.start(plugin, player, "Tekst");
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                TimedLyrics fetched;
                try {
                    if (bar != null) {
                        bar.setStage("Tekst");
                    }
                    fetched = lyricsClient.fetchLyrics(track, bar != null ? bar.asListener() : null);
                } finally {
                    if (bar != null) {
                        bar.close();
                    }
                }
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    KaraokeSession current = sessions.get(playerId);
                    if (current == session) {
                        current.updateTrackAndLyrics(track, fetched, false);
                        plugin.messages().send(player, "loadedLyrics", "&aTekst gotowy.");
                    }
                });
            });
        }
    }

    private KaraokeSession startCachedSession(Player player, Location origin, String audioUrl, TrackInfo track, TimedLyrics lyrics, KaraokeTextColor color) {
        if (player == null) {
            return null;
        }

        if (!plugin.isFancyHologramsReady()) {
            plugin.messages().send(
                    player,
                    "hologramsNotReady",
                    "&cKaraoke jeszcze się ładuje (FancyHolograms nie jest gotowy). Spróbuj za chwilę."
            );
            return null;
        }

        MediaCache cache = plugin.mediaCache();
        if (cache == null) {
            plugin.messages().send(player, "cacheDisabled", "&cCache jest wyłączony lub niedostępny.");
            return null;
        }

        MediaCache.CachedItem item = cache.get(audioUrl);
        java.nio.file.Path audio = item != null ? cache.resolveAudioPath(item) : null;

        Location originToUse = origin != null ? origin.clone() : player.getLocation().clone();
        UUID playerId = player.getUniqueId();

        stop(player, false);

        double exclusionRadius = Math.max(0D, plugin.getConfig().getDouble("karaoke.exclusionRadiusBlocks", 100D));
        if (isAreaOccupied(originToUse, exclusionRadius, playerId)) {
            plugin.messages().send(
                    player,
                    "areaOccupied",
                    "&cW pobliżu ({radius} bloków) działa już karaoke. Spróbuj dalej lub użyj /karaoke stop.",
                    "radius", String.valueOf((int) exclusionRadius)
            );
            return null;
        }

        plugin.messages().send(player, "startingFromCache", "&aStartuję z cache.");

        TimedLyrics safeLyrics = lyrics != null ? lyrics : new PlaceholderTimedLyrics(track);
        final KaraokeSession[] holder = new KaraokeSession[1];
        KaraokeSession session = new KaraokeSession(
                plugin,
                player,
            originToUse,
                track,
                safeLyrics,
                color,
                audio,
                () -> {
                    KaraokeSession current = sessions.get(playerId);
                    if (current != null && current == holder[0]) {
                        sessions.remove(playerId);
                    }
                }
        );
        holder[0] = session;
        sessions.put(playerId, session);
        session.start();
        return session;
    }

    public void pause(Player player) {
        if (player == null) {
            return;
        }
        KaraokeSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            plugin.messages().send(player, "noActiveSession", "&7Nie masz aktywnego karaoke.");
            return;
        }
        session.pause();
        plugin.messages().send(player, "paused", "&ePauza.");
    }

    public void resume(Player player) {
        if (player == null) {
            return;
        }
        KaraokeSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            plugin.messages().send(player, "noActiveSession", "&7Nie masz aktywnego karaoke.");
            return;
        }
        session.resume();
        plugin.messages().send(player, "resumed", "&aWznowiono.");
    }

    private void startInternal(Player player, String link, KaraokeTextColor color, boolean clearQueueBeforeStart) {
        if (player == null) {
            return;
        }

        int maxSessions = Math.max(0, plugin.getConfig().getInt("karaoke.maxConcurrentSessions", 0));
        if (maxSessions > 0 && sessions.size() >= maxSessions) {
            plugin.messages().send(player, "tooManySessions", "&cZa dużo aktywnych karaoke na serwerze. Spróbuj za chwilę.");
            return;
        }

        if (!plugin.isFancyHologramsReady()) {
            plugin.messages().send(
                    player,
                    "hologramsNotReady",
                    "&cKaraoke jeszcze się ładuje (FancyHolograms nie jest gotowy). Spróbuj za chwilę."
            );
            plugin.debug().debug(() -> "Start denied for " + player.getName() + " because FancyHolograms is not enabled yet");
            return;
        }

        Location origin = player.getLocation().clone();
        UUID playerId = player.getUniqueId();

        // Stop own existing session first (so restart is always possible)
        stop(player, clearQueueBeforeStart);

        double exclusionRadius = Math.max(0D, plugin.getConfig().getDouble("karaoke.exclusionRadiusBlocks", 100D));
        if (isAreaOccupied(origin, exclusionRadius, playerId)) {
            plugin.messages().send(
                    player,
                    "areaOccupied",
                    "&cW pobliżu ({radius} bloków) działa już karaoke. Spróbuj dalej lub użyj /karaoke stop.",
                    "radius", String.valueOf((int) exclusionRadius)
            );
            plugin.debug().debug(() -> "Start denied for " + player.getName() + " due to area exclusion radius=" + exclusionRadius);
            return;
        }

        // Reserve the area immediately to avoid races during async metadata fetch
        reservations.put(playerId, new StartReservation(origin));

        plugin.messages().send(player, "loadingMetadata", "&7Ładuję informacje o utworze…");
        plugin.debug().debug(() -> "Starting karaoke (instant) for " + player.getName() + " link=" + safeShort(link));

        // Start immediately with placeholder content so the user sees something right away.
        TrackInfo placeholderTrack = new TrackInfo("(ładowanie…)", null, "loading");
        TimedLyrics placeholderLyrics = new PlaceholderTimedLyrics(placeholderTrack);
        final KaraokeSession[] holder = new KaraokeSession[1];
        KaraokeSession session = new KaraokeSession(
                plugin,
                player,
                origin,
                placeholderTrack,
                placeholderLyrics,
                color,
            null,
                () -> {
                    KaraokeSession current = sessions.get(playerId);
                    if (current != null && current == holder[0]) {
                        sessions.remove(playerId);
                    }
                }
        );
        holder[0] = session;
        sessions.put(playerId, session);
        reservations.remove(playerId);
        session.start();

        // Resolve metadata/lyrics in background and update the running session when ready.
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            ActionBarProgress bar = ActionBarProgress.start(plugin, player, "YouTube");
            try {
                if (bar != null) {
                    bar.setStage("YouTube");
                }
                TrackInfo resolvedTrack = metadataClient.resolve(link, bar != null ? bar.asListener() : null);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        plugin.messages().send(player, "loadedMetadata", "&aInformacje gotowe: &f{title}&7 - &f{author}",
                                "title", resolvedTrack.title(),
                                "author", (resolvedTrack.author() != null ? resolvedTrack.author() : resolvedTrack.source()));
                        plugin.messages().send(player, "loadingLyrics", "&7Pobieram tekst…");
                    }
                });
                if (bar != null) {
                    bar.setStage("Tekst");
                }
                TimedLyrics resolvedLyrics = lyricsClient.fetchLyrics(resolvedTrack, bar != null ? bar.asListener() : null);

                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        plugin.messages().send(player, "loadedLyrics", "&aTekst gotowy.");
                    }
                });

                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        plugin.debug().debug(() -> "Async update aborted (player offline): " + player.getName());
                        stop(player, false);
                        return;
                    }

                    KaraokeSession current = sessions.get(playerId);
                    if (current == null) {
                        return;
                    }

                    // Update content and announce real title/author once.
                    current.updateTrackAndLyrics(resolvedTrack, resolvedLyrics, true);
                });

            } catch (Exception e) {
                plugin.debug().warn("Failed to resolve metadata/lyrics for " + player.getName() + ": " + e.getMessage(), e);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    // Keep session running with placeholder, but inform the user.
                    plugin.messages().send(
                            player,
                            "startFailed",
                            "&cNie udało się uruchomić karaoke: {error}",
                            "error", safeUserError(e)
                    );
                });
            } finally {
                if (bar != null) {
                    bar.close();
                }
            }
        });
    }

    public void stop(Player player) {
        stop(player, true);
    }

    private void stop(Player player, boolean clearQueue) {
        if (player == null) {
            return;
        }

        reservations.remove(player.getUniqueId());

        if (clearQueue) {
            queues.remove(player.getUniqueId());
        }

        KaraokeSession existing = sessions.remove(player.getUniqueId());
        if (existing != null) {
            try {
                existing.stop();
            } catch (Exception e) {
                plugin.debug().warn("Failed to stop session for " + player.getName(), e);
            }
        }
    }

    public void stopAll() {
        reservations.clear();
        queues.clear();
        for (KaraokeSession session : sessions.values()) {
            try {
                session.stop();
            } catch (Exception e) {
                plugin.debug().warn("Failed to stop a session during stopAll", e);
            }
        }
        sessions.clear();
    }

    private boolean isAreaOccupied(Location origin, double radiusBlocks, UUID ignorePlayerId) {
        if (origin == null || origin.getWorld() == null) {
            return false;
        }

        // Expire old reservations so a stuck async start doesn't block an area forever.
        long reservationTtlSeconds = Math.max(1L, plugin.getConfig().getLong("karaoke.startReservationTimeoutSeconds", 30L));

        for (Map.Entry<UUID, KaraokeSession> entry : sessions.entrySet()) {
            if (ignorePlayerId != null && ignorePlayerId.equals(entry.getKey())) {
                continue;
            }
            KaraokeSession session = entry.getValue();
            if (session != null && session.isWithinRadius(origin, radiusBlocks)) {
                return true;
            }
        }
        for (Map.Entry<UUID, StartReservation> entry : reservations.entrySet()) {
            if (ignorePlayerId != null && ignorePlayerId.equals(entry.getKey())) {
                continue;
            }
            StartReservation reservation = entry.getValue();
            if (reservation == null) {
                continue;
            }
            if (reservation.isExpired(reservationTtlSeconds)) {
                reservations.remove(entry.getKey());
                continue;
            }
            if (reservation.isWithin(origin, radiusBlocks)) {
                return true;
            }
        }
        return false;
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

    private static String safeUserError(Exception e) {
        if (e == null || e.getMessage() == null || e.getMessage().isBlank()) {
            return "nieznany błąd";
        }
        // Keep messages short and avoid leaking internal details
        String m = e.getMessage().trim();
        return m.length() > 120 ? (m.substring(0, 117) + "...") : m;
    }

    private static final class StartReservation {
        private final Location origin;
        private final long createdMillis;

        private StartReservation(Location origin) {
            this.origin = origin == null ? null : origin.clone();
            this.createdMillis = System.currentTimeMillis();
        }

        private boolean isExpired(long ttlSeconds) {
            long ttlMs = Math.max(1L, ttlSeconds) * 1000L;
            return (System.currentTimeMillis() - createdMillis) > ttlMs;
        }

        private boolean isWithin(Location loc, double radiusBlocks) {
            if (loc == null || loc.getWorld() == null || origin == null || origin.getWorld() == null) {
                return false;
            }
            if (!loc.getWorld().equals(origin.getWorld())) {
                return false;
            }
            double r = Math.max(0D, radiusBlocks);
            return origin.distanceSquared(loc) <= (r * r);
        }
    }
}
