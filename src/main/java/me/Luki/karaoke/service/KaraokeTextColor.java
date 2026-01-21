package me.Luki.karaoke.service;

import net.kyori.adventure.text.format.NamedTextColor;

public enum KaraokeTextColor {
    RED(NamedTextColor.RED),
    GREEN(NamedTextColor.GREEN),
    BLUE(NamedTextColor.BLUE);

    private final NamedTextColor named;

    KaraokeTextColor(NamedTextColor named) {
        this.named = named;
    }

    public NamedTextColor named() {
        return named;
    }

    public static KaraokeTextColor fromPolish(String value) {
        String v = value.trim().toLowerCase();
        return switch (v) {
            case "czerwony" -> RED;
            case "zielony" -> GREEN;
            case "niebieski" -> BLUE;
            default -> throw new IllegalArgumentException("Unknown color: " + value);
        };
    }
}
