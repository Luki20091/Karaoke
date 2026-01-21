package me.Luki.karaoke.command;

import me.Luki.karaoke.Karaoke;
import me.Luki.karaoke.service.KaraokeService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

public class KaraokeAdminCommand implements CommandExecutor {

    private final Karaoke plugin;
    private final KaraokeService service;

    public KaraokeAdminCommand(Karaoke plugin, KaraokeService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("karaoke.admin")) {
            plugin.messages().send(sender, "noPermission", "&cBrak uprawnień.");
            return true;
        }

        if (args.length == 0) {
            plugin.messages().send(sender, "adminUsage", "&7Użycie: /karaokeadmin <reload|stopall|debug|http|status>");
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "reload" -> {
                plugin.reloadConfig();
                boolean debugEnabled = plugin.getConfig().getBoolean("debug.enabled", false);
                boolean debugHttp = plugin.getConfig().getBoolean("debug.http", false);
                plugin.debug().setEnabled(debugEnabled);
                plugin.debug().setHttpEnabled(debugHttp);
                plugin.messages().send(sender, "adminReloaded", "&aPrzeładowano config.yml (debug={debug}, http={http}).",
                        "debug", String.valueOf(debugEnabled),
                        "http", String.valueOf(debugHttp));
                return true;
            }
            case "stopall" -> {
                service.stopAll();
                plugin.messages().send(sender, "adminStoppedAll", "&aZatrzymano wszystkie sesje karaoke.");
                return true;
            }
            case "debug" -> {
                if (args.length < 2) {
                    plugin.messages().send(sender, "adminDebugUsage", "&7Użycie: /karaokeadmin debug <on|off>");
                    return true;
                }
                boolean on;
                try {
                    on = parseOnOff(args[1]);
                } catch (IllegalArgumentException e) {
                    plugin.messages().send(sender, "adminOnOffExpected", "&cUżyj: on/off");
                    return true;
                }
                plugin.debug().setEnabled(on);
                plugin.getConfig().set("debug.enabled", on);
                plugin.saveConfig();
                plugin.messages().send(sender, "adminDebugSet", "&aUstawiono debug.enabled={value}", "value", String.valueOf(on));
                return true;
            }
            case "http" -> {
                if (args.length < 2) {
                    plugin.messages().send(sender, "adminHttpUsage", "&7Użycie: /karaokeadmin http <on|off>");
                    return true;
                }
                boolean on;
                try {
                    on = parseOnOff(args[1]);
                } catch (IllegalArgumentException e) {
                    plugin.messages().send(sender, "adminOnOffExpected", "&cUżyj: on/off");
                    return true;
                }
                plugin.debug().setHttpEnabled(on);
                plugin.getConfig().set("debug.http", on);
                plugin.saveConfig();
                plugin.messages().send(sender, "adminHttpSet", "&aUstawiono debug.http={value}", "value", String.valueOf(on));
                return true;
            }
            case "status" -> {
                boolean debugEnabled = plugin.getConfig().getBoolean("debug.enabled", false);
                boolean debugHttp = plugin.getConfig().getBoolean("debug.http", false);
                int radius = (int) Math.max(0D, plugin.getConfig().getDouble("karaoke.exclusionRadiusBlocks", 100D));
                int timeout = Math.max(1, plugin.getConfig().getInt("metadata.timeoutSeconds", 10));
                long ttl = Math.max(0L, plugin.getConfig().getLong("metadata.cacheTtlSeconds", 3600L));
                plugin.messages().send(sender, "adminStatus",
                        "&7Status: &fdebug={debug} http={http} radius={radius} timeout={timeout}s cacheTtl={ttl}s",
                        "debug", String.valueOf(debugEnabled),
                        "http", String.valueOf(debugHttp),
                        "radius", String.valueOf(radius),
                        "timeout", String.valueOf(timeout),
                        "ttl", String.valueOf(ttl));
                return true;
            }
            default -> {
                plugin.messages().send(sender, "adminUsage", "&7Użycie: /karaokeadmin <reload|stopall|debug|http|status>");
                return true;
            }
        }
    }

    private static boolean parseOnOff(String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase();
        return switch (v) {
            case "on", "true", "1", "yes", "tak" -> true;
            case "off", "false", "0", "no", "nie" -> false;
            default -> throw new IllegalArgumentException("Expected on/off");
        };
    }
}
