package me.Luki.karaoke.util;

import me.Luki.karaoke.Karaoke;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.HashMap;
import java.util.Map;

public final class Messages {

    private static final LegacyComponentSerializer LEGACY_AMP = LegacyComponentSerializer.legacyAmpersand();

    private final Karaoke plugin;

    public Messages(Karaoke plugin) {
        this.plugin = plugin;
    }

    public void send(@NotNull CommandSender sender, @NotNull String key, @NotNull String fallback) {
        sender.sendMessage(chat(getRaw(key, fallback)));
    }

    public void send(@NotNull CommandSender sender, @NotNull String key, @NotNull String fallback, Object... placeholders) {
        sender.sendMessage(chat(formatRaw(getRaw(key, fallback), placeholders)));
    }

    public void actionBar(@NotNull Player player, @NotNull String key, @NotNull String fallback, Object... placeholders) {
        if (player == null || !player.isOnline()) {
            return;
        }
        try {
            player.sendActionBar(format(getRaw(key, fallback), placeholders));
        } catch (Throwable ignored) {
        }
    }

    public void clearActionBar(@NotNull Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        try {
            player.sendActionBar(Component.empty());
        } catch (Throwable ignored) {
        }
    }

    public Component get(@NotNull String key, @NotNull String fallback) {
        return colorize(getRaw(key, fallback));
    }

    public Component format(@NotNull String raw, Object... placeholders) {
        return colorize(formatRaw(raw, placeholders));
    }

    private String formatRaw(@NotNull String raw, Object... placeholders) {
        String message = raw;
        if (placeholders != null && placeholders.length > 0) {
            if (placeholders.length % 2 != 0) {
                throw new IllegalArgumentException("Placeholders must be in key/value pairs");
            }
            Map<String, String> map = new HashMap<>();
            for (int i = 0; i < placeholders.length; i += 2) {
                String k = String.valueOf(placeholders[i]);
                String v = String.valueOf(placeholders[i + 1]);
                map.put(k, v);
            }
            message = applyPlaceholders(message, map);
        }
        return message;
    }

    private Component chat(@NotNull String rawMessage) {
        String prefix = String.valueOf(plugin.getConfig().getString("messages.prefix", "")).trim();
        if (prefix.isEmpty()) {
            return colorize(rawMessage);
        }

        // Prefix supports legacy & color codes.
        // We keep it as raw text and colorize once to preserve formatting.
        String combined;
        if (prefix.endsWith(" ")) {
            combined = prefix + rawMessage;
        } else {
            combined = prefix + " " + rawMessage;
        }
        return colorize(combined);
    }

    private String getRaw(@NotNull String key, @NotNull String fallback) {
        String path = "messages." + key;
        String value = plugin.getConfig().getString(path);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private static String applyPlaceholders(String message, Map<String, String> placeholders) {
        String out = message;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            String key = e.getKey();
            String val = e.getValue();
            out = out.replace("{" + key + "}", val != null ? val : "");
        }
        return out;
    }

    private static Component colorize(String message) {
        // Supports legacy & color codes used in config.yml
        return LEGACY_AMP.deserialize(message);
    }
}
