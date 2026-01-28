package me.Luki.karaoke.service;

import me.Luki.karaoke.Karaoke;
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
import org.bukkit.Bukkit;
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

    public KaraokeService(Karaoke plugin, PlaylistStore playlistStore) {
        this.plugin = plugin;
        this.playlistStore = playlistStore;
        this.sessions = new ConcurrentHashMap<>();
        this.reservations = new ConcurrentHashMap<>();
        this.queues = new ConcurrentHashMap<>();
        this.metadataClient = new LinkMetadataClient(plugin);
    }

    public Karaoke getPlugin() {
        return plugin;
    }

    public PlaylistStore getPlaylistStore() {
        return playlistStore;
    }

    public void start(Player player, String link, KaraokeTextColor color, Double volume) {
        startInternal(player, link, color, volume, true);
    }

    public void play(Player player, String target, KaraokeTextColor color, Double volume) {
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
            double vol = resolveVolume(volume);
            PlayerQueue q = new PlayerQueue(pl.entries(), color, vol, player.getLocation());
            queues.put(player.getUniqueId(), q);
            PlaylistEntry first = q.current();
            if (first == null) {
                queues.remove(player.getUniqueId());
                plugin.messages().send(player, "playlistEmpty", "&7Playlista &f{name}&7 jest pusta.", "name", pl.name());
                return;
            }

            plugin.messages().send(player, "playlistPlaying", "&aGram playlistę &f{name}&a.", "name", pl.name());
            startFromCacheOrPrefetch(player, q.origin(), first, color, q.volume());
            return;
        }

        // Otherwise, treat as a direct link.
        startInternal(player, target, color, volume, true);
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

        startFromCacheOrPrefetch(player, q.origin(), next, q.color(), q.volume());
    }

    private void startFromCacheOrPrefetch(Player player, Location origin, PlaylistEntry entry, KaraokeTextColor color, double volume) {
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

        // Lyrics: prefer cached LRC from disk.
        // If missing, do NOT fetch during /karaoke. Lyrics must be produced during /playlist add|import|prefetch.
        TimedLyrics lyrics = null;
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
        if (lyrics != null) {
            startCachedSession(player, origin, audioUrl, track, lyrics, color, volume);
            return;
        }

        // Lyrics not cached -> do not start.
        plugin.messages().send(player, "lyricsNotCached", "&eBrak tekstu w cache. Użyj /playlist <nazwa> prefetch lub dodaj/importuj utwór ponownie.");
    }

    private KaraokeSession startCachedSession(Player player, Location origin, String audioUrl, TrackInfo track, TimedLyrics lyrics, KaraokeTextColor color, double volume) {
        if (player == null) {
            return null;
        }

        if (lyrics == null) {
            plugin.messages().send(player, "lyricsNotCached", "&eBrak tekstu w cache. Użyj /playlist <nazwa> prefetch lub dodaj/importuj utwór ponownie.");
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

        final KaraokeSession[] holder = new KaraokeSession[1];
        KaraokeSession session = new KaraokeSession(
                plugin,
                player,
            originToUse,
                track,
                lyrics,
                color,
                volume,
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

    /**
     * Pause either your own session, or (if you don't have one) the nearest active session in your area.
     * This is meant for public karaoke control when players gather near one hologram.
     */
    public void pauseNearbyOrOwned(Player actor) {
        if (actor == null) {
            return;
        }

        ResolvedSession resolved = resolveSessionForControl(actor);
        if (resolved == null) {
            plugin.messages().send(actor, "noActiveOrNearbySession", "&7Nie masz aktywnego karaoke ani żadnego w pobliżu.");
            return;
        }

        resolved.session.pause();
        plugin.messages().send(actor, "paused", "&ePauza.");

        if (!resolved.ownerId.equals(actor.getUniqueId())) {
            Player owner = Bukkit.getPlayer(resolved.ownerId);
            if (owner != null && owner.isOnline()) {
                plugin.messages().send(owner, "pausedByOther", "&eTwoje karaoke zostało zapauzowane przez {player}.",
                        "player", actor.getName());
            }
        }
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

    /**
     * Resume either your own session, or (if you don't have one) the nearest active session in your area.
     */
    public void resumeNearbyOrOwned(Player actor) {
        if (actor == null) {
            return;
        }

        ResolvedSession resolved = resolveSessionForControl(actor);
        if (resolved == null) {
            plugin.messages().send(actor, "noActiveOrNearbySession", "&7Nie masz aktywnego karaoke ani żadnego w pobliżu.");
            return;
        }

        resolved.session.resume();
        plugin.messages().send(actor, "resumed", "&aWznowiono.");

        if (!resolved.ownerId.equals(actor.getUniqueId())) {
            Player owner = Bukkit.getPlayer(resolved.ownerId);
            if (owner != null && owner.isOnline()) {
                plugin.messages().send(owner, "resumedByOther", "&aTwoje karaoke zostało wznowione przez {player}.",
                        "player", actor.getName());
            }
        }
    }

    /**
     * Stop either your own session, or (if you don't have one) the nearest active session in your area.
     * NOTE: This is intentionally separate from stop(Player) so lifecycle hooks (quit/kick) keep stopping only owned sessions.
     */
    public void stopNearbyOrOwned(Player actor) {
        if (actor == null) {
            return;
        }

        ResolvedSession resolved = resolveSessionForControl(actor);
        if (resolved == null) {
            plugin.messages().send(actor, "noActiveOrNearbySession", "&7Nie masz aktywnego karaoke ani żadnego w pobliżu.");
            return;
        }

        // Clear queue for the owner, since /karaoke stop is a hard stop.
        stopByOwnerId(resolved.ownerId, true);
        plugin.messages().send(actor, "stopped", "&aKaraoke zatrzymane.");

        if (!resolved.ownerId.equals(actor.getUniqueId())) {
            Player owner = Bukkit.getPlayer(resolved.ownerId);
            if (owner != null && owner.isOnline()) {
                plugin.messages().send(owner, "stoppedByOther", "&cTwoje karaoke zostało zatrzymane przez {player}.",
                        "player", actor.getName());
            }
        }
    }

    /**
     * Skip either your own playlist session, or (if you don't have one) the nearest active session in your area.
     */
    public void skipNearbyOrOwned(Player actor) {
        if (actor == null) {
            return;
        }

        ResolvedSession resolved = resolveSessionForControl(actor);
        if (resolved == null) {
            plugin.messages().send(actor, "noActiveOrNearbySession", "&7Nie masz aktywnego karaoke ani żadnego w pobliżu.");
            return;
        }

        UUID ownerId = resolved.ownerId;
        Player owner = Bukkit.getPlayer(ownerId);
        if (owner == null || !owner.isOnline()) {
            stopByOwnerId(ownerId, true);
            plugin.messages().send(actor, "stopped", "&aKaraoke zatrzymane.");
            return;
        }

        PlayerQueue q = queues.get(ownerId);
        if (q == null) {
            // Not a playlist session -> treat skip as stop.
            stopByOwnerId(ownerId, true);
            plugin.messages().send(actor, "stopped", "&aKaraoke zatrzymane.");
            return;
        }

        // Stop current track but keep the queue.
        stopByOwnerId(ownerId, false);
        PlaylistEntry next = q.next();
        if (next == null) {
            queues.remove(ownerId);
            plugin.messages().send(actor, "playlistEnded", "&7Koniec playlisty.");
            return;
        }

        startFromCacheOrPrefetch(owner, q.origin(), next, q.color(), q.volume());
        plugin.messages().send(actor, "skipped", "&aPominięto utwór.");

        if (!ownerId.equals(actor.getUniqueId())) {
            plugin.messages().send(owner, "skippedByOther", "&eTwój utwór został pominięty przez {player}.",
                    "player", actor.getName());
        }
    }

    private void startInternal(Player player, String link, KaraokeTextColor color, Double volume, boolean clearQueueBeforeStart) {
        if (player == null) {
            return;
        }

        final double volumeToUse = resolveVolume(volume);

        // Never fetch lyrics during /karaoke. Direct links must be pre-cached via /playlist add|import|prefetch.
        MediaCache cache = plugin.mediaCache();
        if (cache != null) {
            try {
                String lrc = cache.readSyncedLyrics(link);
                if (lrc == null || lrc.isBlank()) {
                    plugin.messages().send(player, "lyricsNotCached", "&eBrak tekstu w cache. Użyj /playlist <nazwa> prefetch lub dodaj/importuj utwór ponownie.");
                    return;
                }
            } catch (Exception ignored) {
                plugin.messages().send(player, "lyricsNotCached", "&eBrak tekstu w cache. Użyj /playlist <nazwa> prefetch lub dodaj/importuj utwór ponownie.");
                return;
            }
        } else {
            plugin.messages().send(player, "cacheDisabled", "&cCache jest wyłączony lub niedostępny.");
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
        plugin.debug().debug(() -> "Starting karaoke (cached) for " + player.getName() + " link=" + safeShort(link));

        // Resolve metadata in background and start session once track is known. Lyrics are already cached.
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            ActionBarProgress bar = ActionBarProgress.start(plugin, player, "YouTube");
            try {
                if (bar != null) {
                    bar.setStage("YouTube");
                }
                TrackInfo resolvedTrack = metadataClient.resolve(link, bar != null ? bar.asListener() : null);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        plugin.debug().debug(() -> "Async update aborted (player offline): " + player.getName());
                        stop(player, false);
                        return;
                    }

                    // Parse cached lyrics
                    TimedLyrics parsedLyrics = null;
                    try {
                        String lrc = cache.readSyncedLyrics(link);
                        if (lrc != null && !lrc.isBlank()) {
                            var parsed = LrcParser.parse(lrc);
                            if (!parsed.isEmpty()) {
                                parsedLyrics = new LrcTimedLyrics(parsed);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                    if (parsedLyrics == null) {
                        plugin.messages().send(player, "lyricsNotCached", "&eBrak tekstu w cache. Użyj /playlist <nazwa> prefetch lub dodaj/importuj utwór ponownie.");
                        return;
                    }

                    plugin.messages().send(player, "loadedMetadata", "&aInformacje gotowe: &f{title}&7 - &f{author}",
                            "title", resolvedTrack.title(),
                            "author", (resolvedTrack.author() != null ? resolvedTrack.author() : resolvedTrack.source()));

                    reservations.remove(playerId);
                    startCachedSession(player, origin, link, resolvedTrack, parsedLyrics, color, volumeToUse);
                });

            } catch (Exception e) {
                plugin.debug().warn("Failed to resolve metadata/lyrics for " + player.getName() + ": " + e.getMessage(), e);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
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

    private double resolveVolume(Double requested) {
        double base = plugin.getConfig().getDouble("svc.volume", 1.0);
        double v = requested != null ? requested : base;
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            v = base;
        }
        if (v < 0.0) {
            v = 0.0;
        }
        if (v > 2.0) {
            v = 2.0;
        }
        return v;
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

    private void stopByOwnerId(UUID ownerId, boolean clearQueue) {
        if (ownerId == null) {
            return;
        }

        reservations.remove(ownerId);
        if (clearQueue) {
            queues.remove(ownerId);
        }

        KaraokeSession existing = sessions.remove(ownerId);
        if (existing != null) {
            try {
                existing.stop();
            } catch (Exception e) {
                plugin.debug().warn("Failed to stop session for ownerId=" + ownerId, e);
            }
        }
    }

    private double controlRadiusBlocks() {
        // Default to the same radius used to prevent multiple sessions in one area.
        // This usually ensures there's at most one controllable session nearby.
        double exclusion = plugin.getConfig().getDouble("karaoke.exclusionRadiusBlocks", 100D);
        return Math.max(0D, plugin.getConfig().getDouble("karaoke.controlRadiusBlocks", exclusion));
    }

    private ResolvedSession resolveSessionForControl(Player actor) {
        UUID actorId = actor.getUniqueId();

        KaraokeSession owned = sessions.get(actorId);
        if (owned != null) {
            return new ResolvedSession(actorId, owned);
        }

        Location loc = actor.getLocation();
        double radius = controlRadiusBlocks();
        ResolvedSession best = null;
        double bestDist2 = Double.MAX_VALUE;

        for (Map.Entry<UUID, KaraokeSession> entry : sessions.entrySet()) {
            UUID ownerId = entry.getKey();
            KaraokeSession session = entry.getValue();
            if (session == null) {
                continue;
            }

            if (!session.isWithinRadius(loc, radius)) {
                continue;
            }

            Location origin = session.originLocation();
            if (origin == null || origin.getWorld() == null || loc.getWorld() == null || !origin.getWorld().equals(loc.getWorld())) {
                continue;
            }
            double d2 = origin.distanceSquared(loc);
            if (d2 < bestDist2) {
                bestDist2 = d2;
                best = new ResolvedSession(ownerId, session);
            }
        }
        return best;
    }

    private record ResolvedSession(UUID ownerId, KaraokeSession session) {
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
