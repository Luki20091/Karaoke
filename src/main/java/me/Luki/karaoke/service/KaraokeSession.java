package me.Luki.karaoke.service;

import me.Luki.karaoke.Karaoke;
import me.Luki.karaoke.hologram.KaraokeHologram;
import me.Luki.karaoke.hologram.KaraokePlacement;
import me.Luki.karaoke.lyrics.TimedLyrics;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import me.Luki.karaoke.meta.TrackInfo;

import java.nio.file.Path;
import java.util.UUID;

public class KaraokeSession {

    private final Karaoke plugin;
    private final Player player;
    private final Location origin;
    private final Runnable onStop;
    private volatile TrackInfo track;
    private volatile TimedLyrics lyrics;
    private final KaraokeTextColor highlightColor;

    private final UUID sessionId;
    private final Path audioFile;

    private Location audioOrigin;
    private long pausedAudioElapsedMs;

    private KaraokeHologram hologram;
    private BukkitTask task;
    private long startMillis;
    private boolean announced;
    private boolean stopped;

    private String lastHeaderTitle;
    private boolean lastHeaderPaused;
    private boolean headerDirty;

    private Component lastTopLine;
    private Component lastMiddleLine;
    private Component lastBottomLine;

    private boolean paused;
    private long pausedAtMillis;
    private long pausedTotalMillis;

    public KaraokeSession(Karaoke plugin, Player player, Location origin, TrackInfo track, TimedLyrics lyrics, KaraokeTextColor highlightColor, Path audioFile, Runnable onStop) {
        this.plugin = plugin;
        this.player = player;
        this.origin = origin == null ? null : origin.clone();
        this.onStop = onStop;
        this.track = track;
        this.lyrics = lyrics;
        this.highlightColor = highlightColor;
        this.sessionId = UUID.randomUUID();
        this.audioFile = audioFile;
        this.announced = false;
        this.stopped = false;
        this.paused = false;
        this.pausedAtMillis = 0L;
        this.pausedTotalMillis = 0L;
        this.audioOrigin = null;
        this.pausedAudioElapsedMs = 0L;

        this.lastHeaderTitle = null;
        this.lastHeaderPaused = false;
        this.headerDirty = true;
        this.lastTopLine = null;
        this.lastMiddleLine = null;
        this.lastBottomLine = null;
    }

    public void start() {
        KaraokePlacement placement = (origin != null) ? KaraokePlacement.fromLocation(plugin, origin) : KaraokePlacement.fromPlayer(plugin, player);
        this.hologram = new KaraokeHologram(plugin, placement);
        this.audioOrigin = placement.baseLocation().clone();
        this.startMillis = System.currentTimeMillis();
        this.paused = false;
        this.pausedAtMillis = 0L;
        this.pausedTotalMillis = 0L;
        this.pausedAudioElapsedMs = 0L;

        // Optional voice playback (SVC)
        try {
            if (plugin.voiceBridge() != null) {
                plugin.voiceBridge().startSessionAudio(sessionId, player, audioOrigin, audioFile);
            }
        } catch (Throwable t) {
            plugin.debug().warn("Failed to start SVC audio; continuing without audio", t);
        }

        plugin.debug().debug(() -> "Session start for " + player.getName() + " at " + placement.baseLocation().getWorld().getName() + " " +
                Math.round(placement.baseLocation().getX()) + "," + Math.round(placement.baseLocation().getY()) + "," + Math.round(placement.baseLocation().getZ()));

        // Initial render (often placeholder while we fetch metadata/lyrics)
        headerDirty = true;
        updateHologramHeader();

        TrackInfo initialTrack = this.track;
        String author = initialTrack != null && initialTrack.author() != null ? initialTrack.author() : (initialTrack != null ? initialTrack.source() : "link");
        hologram.setLyricsLines(
            Component.text("", NamedTextColor.GRAY),
            Component.text(author, NamedTextColor.GRAY),
            Component.text("", NamedTextColor.GRAY)
        );

        this.task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> tick(), 1L, 2L);
    }

    private void tick() {
        try {
            if (!player.isOnline()) {
                stop();
                return;
            }

            long now = System.currentTimeMillis();
            long elapsedMs;
            if (paused) {
                elapsedMs = Math.max(0L, pausedAtMillis - startMillis - pausedTotalMillis);
            } else {
                elapsedMs = Math.max(0L, now - startMillis - pausedTotalMillis);
            }

            TimedLyrics currentLyrics = this.lyrics;
            TimedLyrics.RenderState state = currentLyrics.render(elapsedMs, highlightColor.named(), NamedTextColor.GRAY, NamedTextColor.WHITE);

            updateHologramHeader();

            Component top = state.top();
            Component middle = state.middle();
            Component bottom = state.bottom();
            if (lastTopLine == null || !lastTopLine.equals(top)
                    || lastMiddleLine == null || !lastMiddleLine.equals(middle)
                    || lastBottomLine == null || !lastBottomLine.equals(bottom)) {
                hologram.setLyricsLines(top, middle, bottom);
                lastTopLine = top;
                lastMiddleLine = middle;
                lastBottomLine = bottom;
            }

            if (!paused) {
                long maxDurationMs = Math.max(1L, plugin.getConfig().getLong("karaoke.maxDurationSeconds", 600L) * 1000L);
                if (elapsedMs > maxDurationMs) {
                    plugin.debug().debug(() -> "Session max duration reached for " + player.getName());
                    stop();
                }
            }
        } catch (Exception e) {
            plugin.debug().warn("Session tick failed for " + player.getName() + "; stopping session.", e);
            try {
                plugin.messages().send(player, "sessionErrorStop", "&cKaraoke przerwane (błąd).");
            } catch (Exception ignored) {
            }
            stop();
        }
    }

    private void updateHologramHeader() {
        if (hologram == null) {
            return;
        }

        TrackInfo currentTrack = this.track;
        String title = (currentTrack != null && currentTrack.title() != null) ? currentTrack.title() : "(ładowanie…)";
        boolean pausedNow = paused;

        if (!headerDirty && lastHeaderTitle != null && lastHeaderTitle.equals(title) && lastHeaderPaused == pausedNow) {
            return;
        }
        String status;
        if (pausedNow) {
            status = plugin.getConfig().getString("hologram.statusPaused", "Zatrzymany");
        } else {
            status = plugin.getConfig().getString("hologram.statusPlaying", "Gra");
        }

        String nowPlayingTemplate = plugin.getConfig().getString(
            "hologram.headerNowPlaying",
            "&fAktualnie grający tytuł: &6{title} &7- {status}"
        );
        String eventTemplate = plugin.getConfig().getString(
            "hologram.headerEvent",
            "&7Event Karaoke StremCraft III"
        );

        Component nowPlaying = plugin.messages().format(nowPlayingTemplate,
            "title", title,
            "status", status
        );
        Component event = plugin.messages().format(eventTemplate,
            "title", title,
            "status", status
        );

        hologram.setHeaderLines(nowPlaying, event);

        lastHeaderTitle = title;
        lastHeaderPaused = pausedNow;
        headerDirty = false;
    }

    public boolean isPaused() {
        return paused;
    }

    public void pause() {
        if (paused || stopped) {
            return;
        }
        paused = true;
        pausedAtMillis = System.currentTimeMillis();

        headerDirty = true;

        pausedAudioElapsedMs = Math.max(0L, pausedAtMillis - startMillis - pausedTotalMillis);

        // Also pause audio (SVC) if it is running.
        try {
            if (audioFile != null && plugin.voiceBridge() != null) {
                plugin.voiceBridge().stopSessionAudio(sessionId);
            }
        } catch (Throwable t) {
            plugin.debug().debug(() -> "Failed to pause SVC audio: " + t.getClass().getSimpleName());
        }
    }

    public void resume() {
        if (!paused || stopped) {
            return;
        }
        long now = System.currentTimeMillis();
        long delta = Math.max(0L, now - pausedAtMillis);
        pausedTotalMillis += delta;
        paused = false;
        pausedAtMillis = 0L;

        headerDirty = true;

        // Resume audio from the paused offset.
        try {
            if (audioFile != null && plugin.voiceBridge() != null && audioOrigin != null && audioOrigin.getWorld() != null) {
                plugin.voiceBridge().startSessionAudio(sessionId, player, audioOrigin, audioFile, pausedAudioElapsedMs);
            }
        } catch (Throwable t) {
            plugin.debug().debug(() -> "Failed to resume SVC audio: " + t.getClass().getSimpleName());
        }
    }

    public void updateTrackAndLyrics(TrackInfo newTrack, TimedLyrics newLyrics, boolean announce) {
        if (newTrack != null) {
            this.track = newTrack;
            headerDirty = true;
        }
        if (newLyrics != null) {
            this.lyrics = newLyrics;
        }

        // If hologram is already created, force an immediate header refresh.
        if (hologram != null) {
            updateHologramHeader();
        }

        if (announce && !announced && newTrack != null && player != null && player.isOnline()) {
            String author = newTrack.author() != null ? newTrack.author() : newTrack.source();
            plugin.messages().send(
                    player,
                    "sessionStarted",
                    "&aKaraoke: &f{title} &7- &f{author}",
                    "title", newTrack.title(),
                    "author", author
            );
            announced = true;
        }
    }

    public void stop() {
        if (stopped) {
            return;
        }
        stopped = true;

        plugin.debug().debug(() -> "Session stop for " + (player != null ? player.getName() : "<null>"));

        try {
            if (plugin.voiceBridge() != null) {
                plugin.voiceBridge().stopSessionAudio(sessionId);
            }
        } catch (Throwable ignored) {
        }

        if (task != null) {
            task.cancel();
            task = null;
        }
        if (hologram != null) {
            try {
                hologram.close();
            } catch (Throwable t) {
                plugin.debug().warn("Failed to close hologram cleanly; continuing", t);
            }
            hologram = null;
        }

        lastTopLine = null;
        lastMiddleLine = null;
        lastBottomLine = null;
        lastHeaderTitle = null;
        headerDirty = true;

        try {
            if (onStop != null) {
                onStop.run();
            }
        } catch (Throwable ignored) {
        }
    }

    public Location originLocation() {
        return origin == null ? null : origin.clone();
    }

    public boolean isWithinRadius(Location loc, double radiusBlocks) {
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
