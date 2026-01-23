package me.Luki.karaoke.lyrics;

import me.Luki.karaoke.meta.TrackInfo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public class PlaceholderTimedLyrics implements TimedLyrics {

    private final TrackInfo track;

    public PlaceholderTimedLyrics(TrackInfo track) {
        this.track = track;
    }

    @Override
    public RenderState render(long elapsedMs, NamedTextColor highlight, NamedTextColor past, NamedTextColor future) {
        int phase = (int) ((Math.max(0L, elapsedMs) / 400L) % 4);
        String dots = switch (phase) {
            case 0 -> "";
            case 1 -> ".";
            case 2 -> "..";
            default -> "...";
        };

        String title = (track != null && track.title() != null) ? track.title() : "(brak tytułu)";
        String author = (track != null && track.author() != null) ? track.author() : (track != null ? track.source() : "link");

        Component top = Component.text(title, NamedTextColor.GOLD);
        Component middle = Component.text(author, NamedTextColor.GRAY);
        Component bottom = Component.text("Ładowanie tekstu" + dots, highlight);
        return new RenderState(top, middle, bottom);
    }
}
