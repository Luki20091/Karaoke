package me.Luki.karaoke.command;

import me.Luki.karaoke.service.KaraokeService;
import me.Luki.karaoke.service.KaraokeTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

public class KaraokeCommand implements CommandExecutor {

    private final KaraokeService karaokeService;

    public KaraokeCommand(KaraokeService karaokeService) {
        this.karaokeService = karaokeService;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        return execute(sender, label, args);
    }

    public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            karaokeService.getPlugin().messages().send(sender, "onlyPlayers", "Ta komenda jest tylko dla graczy.");
            return true;
        }

        // Basic debug: who ran what
        if (karaokeService != null) {
            karaokeService.getPlugin().debug().debug(() -> "/karaoke by " + player.getName() + " args=" + String.join(" ", args));
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("stop")) {
            // /karaoke stop -> stop your own session
            if (args.length == 1) {
                karaokeService.stop(player);
                karaokeService.getPlugin().messages().send(player, "stopped", "&aKaraoke zatrzymane.");
                return true;
            }

            // /karaoke stop <player> -> only OP/admin can stop someone else's session
            if (!player.isOp() && !player.hasPermission("karaoke.admin")) {
                karaokeService.getPlugin().messages().send(player, "stopOthersNoPermission", "&cMożesz zatrzymać tylko swoje karaoke.");
                return true;
            }

            String targetName = args[1].trim();
            if (targetName.isEmpty()) {
                karaokeService.getPlugin().messages().send(player, "stopOtherUsage", "&7Użycie: /karaoke stop <nick>");
                return true;
            }

            Player target = Bukkit.getPlayerExact(targetName);
            if (target == null) {
                karaokeService.getPlugin().messages().send(player, "stopOtherNotOnline", "&cTen gracz nie jest online.");
                return true;
            }

            karaokeService.stop(target);
            karaokeService.getPlugin().messages().send(player, "stoppedOtherSender", "&aZatrzymano karaoke gracza {player}.",
                    "player", target.getName());
            karaokeService.getPlugin().messages().send(target, "stoppedOtherTarget", "&cTwoje karaoke zostało zatrzymane przez admina.");
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("pause")) {
            if (args.length != 1) {
                karaokeService.getPlugin().messages().send(player, "pauseUsage", "&7Użycie: /karaoke pause");
                return true;
            }
            karaokeService.pause(player);
            return true;
        }

        if (args.length >= 1 && (args[0].equalsIgnoreCase("resume") || args[0].equalsIgnoreCase("play"))) {
            // NOTE: "play" is also used as a subcommand below; here it's resume only when used as single arg.
            if (args[0].equalsIgnoreCase("resume") && args.length == 1) {
                karaokeService.resume(player);
                return true;
            }
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("skip")) {
            if (args.length != 1) {
                karaokeService.getPlugin().messages().send(player, "skipUsage", "&7Użycie: /karaoke skip");
                return true;
            }
            karaokeService.skip(player);
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("play")) {
            if (args.length < 3) {
                karaokeService.getPlugin().messages().send(player, "playUsage", "&7Użycie: /karaoke play <link|playlist> <kolor>");
                return true;
            }

            String target = args[1];
            KaraokeTextColor color;
            try {
                color = KaraokeTextColor.fromPolish(args[2]);
            } catch (IllegalArgumentException e) {
                karaokeService.getPlugin().messages().send(player, "invalidColor", "&cNieprawidłowy kolor. Użyj: czerwony/zielony/niebieski");
                return true;
            }

            karaokeService.play(player, target, color);
            return true;
        }

        if (args.length < 2) {
            boolean allowDirect = karaokeService.getPlugin().getConfig().getBoolean("karaoke.allowDirectLinks", true);
            if (!allowDirect) {
                karaokeService.getPlugin().messages().send(player, "directLinksDisabled",
                        "&eBezpośrednie linki są wyłączone. Najpierw dodaj utwór do playlisty: &f/playlist add <pl> <url> [nazwa...]&e, potem: &f/karaoke play <pl> <kolor>");
                return true;
            }
            karaokeService.getPlugin().messages().send(player, "provideLink", "&cPodaj link do utworu.");
            return true;
        }

        String link = args[0];
        if (link == null || link.trim().isEmpty()) {
            karaokeService.getPlugin().messages().send(player, "provideLink", "&cPodaj link do utworu.");
            return true;
        }

        boolean allowDirect = karaokeService.getPlugin().getConfig().getBoolean("karaoke.allowDirectLinks", true);
        if (!allowDirect) {
            karaokeService.getPlugin().messages().send(player, "directLinksDisabled",
                    "&eBezpośrednie linki są wyłączone. Użyj: &f/karaoke play <playlist> <kolor>&e.");
            return true;
        }

        KaraokeTextColor color;
        try {
            color = KaraokeTextColor.fromPolish(args[1]);
        } catch (IllegalArgumentException e) {
            karaokeService.getPlugin().messages().send(player, "invalidColor", "&cNieprawidłowy kolor. Użyj: czerwony/zielony/niebieski");
            return true;
        }

        karaokeService.start(player, link, color);
        return true;
    }

    public Collection<String> suggest(@NotNull CommandSender sender, @NotNull String[] args) {
        List<String> out = new ArrayList<>();

        // When the user types "/karaoke " and hits tab, Paper may pass args.length==0.
        if (args.length == 0) {
            out.addAll(List.of("play", "pause", "resume", "skip", "stop"));
            return out;
        }

        if (args.length == 1) {
            String prefix = args[0] == null ? "" : args[0].toLowerCase(Locale.ROOT);
            for (String sub : List.of("play", "pause", "resume", "skip", "stop")) {
                if (sub.startsWith(prefix)) {
                    out.add(sub);
                }
            }
            return out;
        }

        if (args.length == 2 && "stop".equalsIgnoreCase(args[0])) {
            // /karaoke stop <nick>
            if (sender.isOp() || sender.hasPermission("karaoke.admin")) {
                String prefix = args[1] == null ? "" : args[1].toLowerCase(Locale.ROOT);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        out.add(p.getName());
                    }
                }
            }
            return out;
        }

        if (args.length == 2) {
            // /karaoke play <...>
            if ("play".equalsIgnoreCase(args[0])) {
                String prefix = args[1] == null ? "" : args[1].toLowerCase(Locale.ROOT);
                try {
                    var store = karaokeService != null ? karaokeService.getPlaylistStore() : null;
                    if (store != null) {
                        for (String name : store.listNames()) {
                            if (name != null && name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                                out.add(name);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                return out;
            }

            // /karaoke <link> <color>
            String prefix = args[1] == null ? "" : args[1].toLowerCase(Locale.ROOT);
            for (String c : List.of("czerwony", "zielony", "niebieski")) {
                if (c.startsWith(prefix)) {
                    out.add(c);
                }
            }
            return out;
        }

        if (args.length == 3 && "play".equalsIgnoreCase(args[0])) {
            // /karaoke play <link|playlist> <color>
            String prefix = args[2] == null ? "" : args[2].toLowerCase(Locale.ROOT);
            for (String c : List.of("czerwony", "zielony", "niebieski")) {
                if (c.startsWith(prefix)) {
                    out.add(c);
                }
            }
            return out;
        }

        return out;
    }
}
