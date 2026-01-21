package me.Luki.karaoke.lyrics;

import me.Luki.karaoke.Karaoke;
import me.Luki.karaoke.meta.TrackInfo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public class PlaceholderTimedLyrics implements TimedLyrics {

    private final Karaoke plugin;
    private final TrackInfo track;

    public PlaceholderTimedLyrics(Karaoke plugin, TrackInfo track) {
        this.plugin = plugin;
        this.track = track;
    }

    @Override
    public RenderState render(long elapsedMs, NamedTextColor highlight, NamedTextColor past, NamedTextColor future) {
        long maxDurationMs = Math.max(1L, plugin.getConfig().getLong("karaoke.maxDurationSeconds", 600L) * 1000L);
        long duration = maxDurationMs;
        int percent = (int) Math.min(100, Math.max(0, (elapsedMs * 100L) / duration));

        String title = (track != null && track.title() != null) ? track.title() : "(brak tytułu)";
        String author = (track != null && track.author() != null) ? track.author() : (track != null ? track.source() : "link");

        Component top = Component.text(title, NamedTextColor.GOLD);
        Component middle = Component.text(author, NamedTextColor.GRAY);
        Component bottom = Component.text("Ładowanie tekstu… " + percent + "%", highlight);
        return new RenderState(top, middle, bottom);
    }
}
