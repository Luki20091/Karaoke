package me.Luki.karaoke.playlist;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public final class Playlist {

    private final String name;
    private final List<PlaylistEntry> entries;

    public Playlist(@NotNull String name) {
        this.name = name;
        this.entries = new ArrayList<>();
    }

    public Playlist(@NotNull String name, @NotNull List<PlaylistEntry> entries) {
        this.name = name;
        this.entries = new ArrayList<>(entries);
    }

    public String name() {
        return name;
    }

    public List<PlaylistEntry> entries() {
        return entries;
    }
}
