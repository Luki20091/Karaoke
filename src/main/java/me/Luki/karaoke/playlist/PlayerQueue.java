package me.Luki.karaoke.playlist;

import me.Luki.karaoke.service.KaraokeTextColor;

import java.util.ArrayList;
import java.util.List;

public final class PlayerQueue {

    private final List<PlaylistEntry> entries;
    private int index;
    private KaraokeTextColor color;

    public PlayerQueue(List<PlaylistEntry> entries, KaraokeTextColor color) {
        this.entries = new ArrayList<>(entries);
        this.index = 0;
        this.color = color;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public PlaylistEntry current() {
        if (index < 0 || index >= entries.size()) {
            return null;
        }
        return entries.get(index);
    }

    public PlaylistEntry next() {
        index++;
        if (index < 0 || index >= entries.size()) {
            return null;
        }
        return entries.get(index);
    }

    public KaraokeTextColor color() {
        return color;
    }

    public void setColor(KaraokeTextColor color) {
        this.color = color;
    }
}
