package me.Luki.karaoke.command;

import me.Luki.karaoke.Karaoke;
import me.Luki.karaoke.audio.FfmpegInstaller;
import me.Luki.karaoke.service.KaraokeService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

public class KaraokeAdminCommand implements CommandExecutor {

    private final Karaoke plugin;
    private final KaraokeService service;

    public KaraokeAdminCommand(Karaoke plugin, KaraokeService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        return execute(sender, label, args);
    }

    public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("karaoke.admin")) {
            plugin.messages().send(sender, "noPermission", "&cBrak uprawnień.");
            return true;
        }

        if (args.length == 0) {
            plugin.messages().send(sender, "adminUsage", "&7Użycie: /" + label + " <reload|stopall|debug|http|status>");
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

                try {
                    if (plugin.voiceBridge() != null) {
                        plugin.voiceBridge().reloadFromConfig();
                    }
                } catch (Throwable ignored) {
                }

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

                String ffmpegPath = String.valueOf(plugin.getConfig().getString("audio.ffmpegPath", "ffmpeg")).trim();
                boolean ffmpegAutoDownload = plugin.getConfig().getBoolean("audio.autoDownloadFfmpeg", false);
                String ffmpegUrl = FfmpegInstaller.selectDownloadUrlNoSideEffects(plugin);

                String os = String.valueOf(System.getProperty("os.name", "")).toLowerCase(Locale.ROOT);
                boolean windows = os.contains("win");
                Path installDir = plugin.getDataFolder().toPath().resolve("tools").resolve("ffmpeg");
                Path bundled = installDir.resolve(windows ? "ffmpeg.exe" : "ffmpeg");

                String ffmpegSource;
                if (!ffmpegPath.equalsIgnoreCase("ffmpeg")) {
                    ffmpegSource = Files.exists(Path.of(ffmpegPath)) ? "CONFIG" : "CONFIG_MISSING";
                } else if (FfmpegInstaller.isFfmpegOnPath()) {
                    ffmpegSource = "PATH";
                } else if (Files.exists(bundled)) {
                    ffmpegSource = "BUNDLED";
                } else {
                    ffmpegSource = "MISSING";
                }

                boolean svcEnabled = plugin.isSvcEnabled();
                boolean svcHooked = plugin.isSvcHooked();
                String svcHost = plugin.getSvcHost();
                int svcPort = plugin.getSvcPort();
                int svcDistance = (int) Math.max(0D, plugin.getSvcDistanceBlocks());

                plugin.messages().send(sender, "adminStatus",
                    "&7Status: &fdebug={debug} http={http} radius={radius} timeout={timeout}s cacheTtl={ttl}s ffmpeg={ffmpegSource} ffmpegPath={ffmpegPath} autoDl={ffmpegAutoDownload} ffmpegUrl={ffmpegUrl} svc={svcEnabled} hooked={svcHooked} host={svcHost} port={svcPort} dist={svcDistance}",
                        "debug", String.valueOf(debugEnabled),
                        "http", String.valueOf(debugHttp),
                        "radius", String.valueOf(radius),
                        "timeout", String.valueOf(timeout),
                    "ttl", String.valueOf(ttl),
                    "ffmpegSource", String.valueOf(ffmpegSource),
                    "ffmpegPath", String.valueOf(ffmpegPath),
                    "ffmpegAutoDownload", String.valueOf(ffmpegAutoDownload),
                    "ffmpegUrl", String.valueOf(ffmpegUrl),
                    "svcEnabled", String.valueOf(svcEnabled),
                    "svcHooked", String.valueOf(svcHooked),
                    "svcHost", String.valueOf(svcHost),
                    "svcPort", String.valueOf(svcPort),
                    "svcDistance", String.valueOf(svcDistance));
                return true;
            }
            default -> {
                plugin.messages().send(sender, "adminUsage", "&7Użycie: /" + label + " <reload|stopall|debug|http|status>");
                return true;
            }
        }
    }

    public Collection<String> suggest(@NotNull CommandSender sender, @NotNull String[] args) {
        List<String> out = new ArrayList<>();

        if (!sender.hasPermission("karaoke.admin")) {
            return out;
        }

        if (args.length == 1) {
            String prefix = args[0] == null ? "" : args[0].toLowerCase(Locale.ROOT);
            for (String sub : List.of("reload", "stopall", "debug", "http", "status")) {
                if (sub.startsWith(prefix)) {
                    out.add(sub);
                }
            }
            return out;
        }

        if (args.length == 2 && ("debug".equalsIgnoreCase(args[0]) || "http".equalsIgnoreCase(args[0]))) {
            String prefix = args[1] == null ? "" : args[1].toLowerCase(Locale.ROOT);
            for (String v : List.of("on", "off")) {
                if (v.startsWith(prefix)) {
                    out.add(v);
                }
            }
            return out;
        }

        return out;
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
