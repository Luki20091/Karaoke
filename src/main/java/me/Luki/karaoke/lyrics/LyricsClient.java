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
            String query;
            String artistNorm = normalizeArtist(artist);
            if (title != null && artistNorm != null && !artistNorm.isBlank()) {
                query = title + " " + artistNorm;
            } else if (title != null) {
                query = title;
            } else {
                query = null;
            }

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

    private static String normalizeArtist(String artist) {
        if (artist == null) {
            return null;
        }
        String a = artist.trim();
        if (a.isBlank()) {
            return a;
        }
        // YouTube oEmbed often returns "Artist - Topic" which harms lyrics search.
        if (a.toLowerCase().endsWith("- topic")) {
            a = a.substring(0, a.length() - "- topic".length()).trim();
        }
        return a;
    }
}
