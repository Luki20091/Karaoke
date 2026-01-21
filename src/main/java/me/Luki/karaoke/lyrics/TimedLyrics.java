package me.Luki.karaoke.lyrics;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public interface TimedLyrics {

    RenderState render(long elapsedMs, NamedTextColor highlight, NamedTextColor past, NamedTextColor future);

    record RenderState(Component top, Component middle, Component bottom) {
    }
}
