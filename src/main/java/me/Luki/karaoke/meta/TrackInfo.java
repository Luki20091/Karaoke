package me.Luki.karaoke.meta;

import org.jetbrains.annotations.Nullable;

public record TrackInfo(
        String title,
        @Nullable String author,
        String source
) {
}
