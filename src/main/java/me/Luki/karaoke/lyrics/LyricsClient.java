package me.Luki.karaoke.lyrics;

import me.Luki.karaoke.Karaoke;
import me.Luki.karaoke.meta.TrackInfo;
import me.Luki.karaoke.util.ProgressListener;

public class LyricsClient {

    private final Karaoke plugin;
    private final LrclibClient lrclib;

    public LyricsClient(Karaoke plugin) {
        this.plugin = plugin;
        this.lrclib = new LrclibClient(plugin);
    }

    public TimedLyrics fetchLyrics(TrackInfo track) {
        return fetchLyrics(track, null);
    }

    public TimedLyrics fetchLyrics(TrackInfo track, ProgressListener progress) {
        String title = track != null ? track.title() : null;
        String artist = track != null ? track.author() : null;
        plugin.debug().debug(() -> "Fetching lyrics for: " + (title != null ? title : "<null>") + " / " + (artist != null ? artist : "<null>"));

        boolean enabled = plugin.getConfig().getBoolean("lyrics.enabled", true);
        if (!enabled) {
            return new PlaceholderTimedLyrics(track);
        }

        try {
            String query = LrclibClient.buildSearchQuery(title, artist);

            String lrc = lrclib.searchLrc(query, progress);
            if (lrc == null || lrc.isBlank()) {
                return new PlaceholderTimedLyrics(track);
            }

            var parsed = LrcParser.parse(lrc);
            if (parsed.isEmpty()) {
                return new PlaceholderTimedLyrics(track);
            }
            return new LrcTimedLyrics(parsed);
        } catch (Exception e) {
            plugin.debug().warn("Lyrics fetch failed; using placeholder", e);
            return new PlaceholderTimedLyrics(track);
        }
    }
}
