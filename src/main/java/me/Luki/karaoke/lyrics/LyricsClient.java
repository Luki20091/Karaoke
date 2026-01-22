package me.Luki.karaoke.lyrics;

import me.Luki.karaoke.Karaoke;
import me.Luki.karaoke.meta.TrackInfo;

public class LyricsClient {

    private final Karaoke plugin;
    private final LrclibClient lrclib;

    public LyricsClient(Karaoke plugin) {
        this.plugin = plugin;
        this.lrclib = new LrclibClient(plugin);
    }

    public TimedLyrics fetchLyrics(TrackInfo track) {
        String title = track != null ? track.title() : null;
        String artist = track != null ? track.author() : null;
        plugin.debug().debug(() -> "Fetching lyrics for: " + (title != null ? title : "<null>") + " / " + (artist != null ? artist : "<null>"));

        boolean enabled = plugin.getConfig().getBoolean("lyrics.enabled", true);
        if (!enabled) {
            return new PlaceholderTimedLyrics(plugin, track);
        }

        try {
            String query;
            if (title != null && artist != null && !artist.isBlank()) {
                query = title + " " + artist;
            } else if (title != null) {
                query = title;
            } else {
                query = null;
            }

            String lrc = lrclib.searchSyncedLrc(query);
            if (lrc == null || lrc.isBlank()) {
                return new PlaceholderTimedLyrics(plugin, track);
            }

            var parsed = LrcParser.parse(lrc);
            if (parsed.isEmpty()) {
                return new PlaceholderTimedLyrics(plugin, track);
            }
            return new LrcTimedLyrics(parsed);
        } catch (Exception e) {
            plugin.debug().warn("Lyrics fetch failed; using placeholder", e);
            return new PlaceholderTimedLyrics(plugin, track);
        }
    }
}
