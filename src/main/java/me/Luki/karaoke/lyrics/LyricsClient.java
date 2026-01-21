package me.Luki.karaoke.lyrics;

import me.Luki.karaoke.Karaoke;
import me.Luki.karaoke.meta.TrackInfo;

public class LyricsClient {

    private final Karaoke plugin;

    public LyricsClient(Karaoke plugin) {
        this.plugin = plugin;
    }

    public TimedLyrics fetchLyrics(TrackInfo track) {
        plugin.debug().debug(() -> "Fetching lyrics (placeholder) for: " + (track != null ? track.title() : "<null>"));
        // Implemented in step 3 (LRCLIB + LRC parsing).
        // For now return a placeholder that shows title and a simple progress marker.
        return new PlaceholderTimedLyrics(plugin, track);
    }
}
