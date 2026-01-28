package me.Luki.karaoke.playlist;

import me.Luki.karaoke.service.KaraokeTextColor;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;

public final class PlayerQueue {

    private final List<PlaylistEntry> entries;
    private int index;
    private KaraokeTextColor color;
    private double volume;
    private final Location origin;

    public PlayerQueue(List<PlaylistEntry> entries, KaraokeTextColor color, double volume, Location origin) {
        this.entries = new ArrayList<>(entries);
        this.index = 0;
        this.color = color;
        this.volume = volume;
        this.origin = origin == null ? null : origin.clone();
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

    public double volume() {
        return volume;
    }

    public Location origin() {
        return origin == null ? null : origin.clone();
    }

    public void setColor(KaraokeTextColor color) {
        this.color = color;
    }

    public void setVolume(double volume) {
        this.volume = volume;
    }
}
