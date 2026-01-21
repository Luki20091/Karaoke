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

public class KaraokeSession {

    private final Karaoke plugin;
    private final Player player;
    private final Location origin;
    private final TrackInfo track;
    private final TimedLyrics lyrics;
    private final KaraokeTextColor highlightColor;

    private KaraokeHologram hologram;
    private BukkitTask task;
    private long startMillis;

    public KaraokeSession(Karaoke plugin, Player player, Location origin, TrackInfo track, TimedLyrics lyrics, KaraokeTextColor highlightColor) {
        this.plugin = plugin;
        this.player = player;
        this.origin = origin == null ? null : origin.clone();
        this.track = track;
        this.lyrics = lyrics;
        this.highlightColor = highlightColor;
    }

    public void start() {
        KaraokePlacement placement = (origin != null) ? KaraokePlacement.fromLocation(plugin, origin) : KaraokePlacement.fromPlayer(plugin, player);
        this.hologram = new KaraokeHologram(plugin, placement);
        this.startMillis = System.currentTimeMillis();

        plugin.debug().debug(() -> "Session start for " + player.getName() + " at " + placement.baseLocation().getWorld().getName() + " " +
                Math.round(placement.baseLocation().getX()) + "," + Math.round(placement.baseLocation().getY()) + "," + Math.round(placement.baseLocation().getZ()));

        String author = track.author() != null ? track.author() : track.source();
        plugin.messages().send(
            player,
            "sessionStarted",
            "&aKaraoke: &f{title} &7- &f{author}",
            "title", track.title(),
            "author", author
        );

        // Initial render
        hologram.setLines(
                Component.text(track.title(), NamedTextColor.GOLD),
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

            long elapsedMs = System.currentTimeMillis() - startMillis;

            TimedLyrics.RenderState state = lyrics.render(elapsedMs, highlightColor.named(), NamedTextColor.GRAY, NamedTextColor.WHITE);
            hologram.setLines(state.top(), state.middle(), state.bottom());

            long maxDurationMs = Math.max(1L, plugin.getConfig().getLong("karaoke.maxDurationSeconds", 600L) * 1000L);
            if (elapsedMs > maxDurationMs) {
                plugin.debug().debug(() -> "Session max duration reached for " + player.getName());
                stop();
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

    public void stop() {
        plugin.debug().debug(() -> "Session stop for " + (player != null ? player.getName() : "<null>"));
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (hologram != null) {
            hologram.close();
            hologram = null;
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
