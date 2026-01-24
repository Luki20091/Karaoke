package me.Luki.karaoke.service;

import net.kyori.adventure.text.format.NamedTextColor;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

public enum KaraokeTextColor {
    BLACK(NamedTextColor.BLACK, '0'),
    DARK_BLUE(NamedTextColor.DARK_BLUE, '1'),
    DARK_GREEN(NamedTextColor.DARK_GREEN, '2'),
    DARK_AQUA(NamedTextColor.DARK_AQUA, '3'),
    DARK_RED(NamedTextColor.DARK_RED, '4'),
    DARK_PURPLE(NamedTextColor.DARK_PURPLE, '5'),
    GOLD(NamedTextColor.GOLD, '6'),
    GRAY(NamedTextColor.GRAY, '7'),
    DARK_GRAY(NamedTextColor.DARK_GRAY, '8'),
    BLUE(NamedTextColor.BLUE, '9'),
    GREEN(NamedTextColor.GREEN, 'a'),
    AQUA(NamedTextColor.AQUA, 'b'),
    RED(NamedTextColor.RED, 'c'),
    LIGHT_PURPLE(NamedTextColor.LIGHT_PURPLE, 'd'),
    YELLOW(NamedTextColor.YELLOW, 'e'),
    WHITE(NamedTextColor.WHITE, 'f');

    private final NamedTextColor named;
    private final char legacyCode;

    KaraokeTextColor(NamedTextColor named, char legacyCode) {
        this.named = named;
        this.legacyCode = legacyCode;
    }

    public NamedTextColor named() {
        return named;
    }

    /**
     * Returns the legacy '&' color code for this color (e.g. "&c" for red).
     * Useful for injecting into config-driven templates before legacy parsing.
     */
    public String legacyAmpersandCode() {
        return "&" + legacyCode;
    }

    /**
     * Canonical Polish names used for suggestions in tab-complete.
     * (Aliases like "żółty" or "dark_blue" are also accepted by {@link #fromPolish(String)}.)
     */
    public static List<String> suggestedPolishNames() {
        return List.of(
                "czarny",
                "granatowy",
                "ciemnozielony",
                "ciemnomorski",
                "ciemnoczerwony",
                "ciemnofioletowy",
                "zloto",
                "szary",
                "ciemnoszary",
                "niebieski",
                "zielony",
                "morski",
                "czerwony",
                "rozowy",
                "zolty",
                "bialy"
        );
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        v = v.replace(' ', '_').replace('-', '_');
        // Strip Polish diacritics (żółty -> zolty)
        v = Normalizer.normalize(v, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return v;
    }

    public static KaraokeTextColor fromPolish(String value) {
        String v = normalize(value);
        return switch (v) {
            case "czarny", "black" -> BLACK;

            case "granatowy", "ciemnoniebieski", "ciemny_niebieski", "dark_blue", "darkblue" -> DARK_BLUE;
            case "ciemnozielony", "ciemny_zielony", "dark_green", "darkgreen" -> DARK_GREEN;
            case "ciemnomorski", "ciemny_morski", "dark_aqua", "darkaqua" -> DARK_AQUA;
            case "ciemnoczerwony", "ciemny_czerwony", "dark_red", "darkred" -> DARK_RED;
            case "ciemnofioletowy", "ciemny_fioletowy", "dark_purple", "darkpurple" -> DARK_PURPLE;

            case "zloto", "zloty", "pomaranczowy", "gold", "orange" -> GOLD;
            case "szary", "gray", "grey" -> GRAY;
            case "ciemnoszary", "ciemny_szary", "dark_gray", "darkgrey", "dark_grey" -> DARK_GRAY;

            case "niebieski", "blue" -> BLUE;
            case "zielony", "green" -> GREEN;
            case "morski", "turkusowy", "aqua", "cyan" -> AQUA;
            case "czerwony", "red" -> RED;
            case "rozowy", "jasnofioletowy", "jasny_fioletowy", "light_purple", "lightpurple", "magenta", "pink" -> LIGHT_PURPLE;
            case "zolty", "yellow" -> YELLOW;
            case "bialy", "white" -> WHITE;

            default -> throw new IllegalArgumentException("Unknown color: " + value);
        };
    }
}
