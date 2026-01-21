package me.Luki.karaoke.service;

import me.Luki.karaoke.Karaoke;
import me.Luki.karaoke.lyrics.LyricsClient;
import me.Luki.karaoke.lyrics.TimedLyrics;
import me.Luki.karaoke.meta.LinkMetadataClient;
import me.Luki.karaoke.meta.TrackInfo;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class KaraokeService {

    private final Karaoke plugin;
    private final Map<UUID, KaraokeSession> sessions;
    private final Map<UUID, StartReservation> reservations;

    private final LinkMetadataClient metadataClient;
    private final LyricsClient lyricsClient;

    public KaraokeService(Karaoke plugin) {
        this.plugin = plugin;
        this.sessions = new ConcurrentHashMap<>();
        this.reservations = new ConcurrentHashMap<>();
        this.metadataClient = new LinkMetadataClient(plugin);
        this.lyricsClient = new LyricsClient(plugin);
    }

    public Karaoke getPlugin() {
        return plugin;
    }

    public void start(Player player, String link, KaraokeTextColor color) {
        if (player == null) {
            return;
        }

        Location origin = player.getLocation().clone();
        UUID playerId = player.getUniqueId();

        // Stop own existing session first (so restart is always possible)
        stop(player);

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
        plugin.debug().debug(() -> "Starting karaoke for " + player.getName() + " link=" + safeShort(link));

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                TrackInfo track = metadataClient.resolve(link);
                TimedLyrics lyrics = lyricsClient.fetchLyrics(track);

                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    // Player may have logged out while we were fetching metadata
                    if (!player.isOnline()) {
                        reservations.remove(playerId);
                        plugin.debug().debug(() -> "Start aborted (player offline): " + player.getName());
                        return;
                    }

                    // Final guard: ensure area is still free (another start might have completed first)
                    double radiusNow = Math.max(0D, plugin.getConfig().getDouble("karaoke.exclusionRadiusBlocks", 100D));
                    if (isAreaOccupied(origin, radiusNow, playerId)) {
                        reservations.remove(playerId);
                        plugin.messages().send(
                                player,
                                "areaOccupiedLate",
                                "&cW pobliżu ({radius} bloków) działa już karaoke.",
                                "radius", String.valueOf((int) radiusNow)
                        );
                        plugin.debug().debug(() -> "Start denied late for " + player.getName() + " due to area exclusion radius=" + radiusNow);
                        return;
                    }

                    KaraokeSession session = new KaraokeSession(plugin, player, origin, track, lyrics, color);
                    sessions.put(playerId, session);
                    reservations.remove(playerId);
                    session.start();
                });

            } catch (Exception e) {
                reservations.remove(playerId);
                plugin.debug().warn("Failed to start karaoke for " + player.getName() + ": " + e.getMessage(), e);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        plugin.messages().send(
                                player,
                                "startFailed",
                                "&cNie udało się uruchomić karaoke: {error}",
                                "error", safeUserError(e)
                        );
                    }
                });
            }
        });
    }

    public void stop(Player player) {
        if (player == null) {
            return;
        }

        reservations.remove(player.getUniqueId());

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
