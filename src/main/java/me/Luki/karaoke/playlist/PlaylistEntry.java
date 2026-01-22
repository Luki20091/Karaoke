package me.Luki.karaoke.playlist;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public record PlaylistEntry(
        @NotNull String name,
        /** Source link (e.g. YouTube) used for metadata/lyrics. */
        @NotNull String url,
        /** Direct audio link (mp3/ogg or file:) that will be downloaded/cached for playback. */
        @Nullable String fileUrl,
        @Nullable String cachedTitle,
        @Nullable String cachedAuthor
) {
}
