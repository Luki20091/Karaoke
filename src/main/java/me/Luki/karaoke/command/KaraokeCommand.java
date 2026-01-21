package me.Luki.karaoke.command;

import me.Luki.karaoke.service.KaraokeService;
import me.Luki.karaoke.service.KaraokeTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class KaraokeCommand implements CommandExecutor {

    private final KaraokeService karaokeService;

    public KaraokeCommand(KaraokeService karaokeService) {
        this.karaokeService = karaokeService;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
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

        if (args.length < 2) {
            return false;
        }

        String link = args[0];
        if (link == null || link.trim().isEmpty()) {
            karaokeService.getPlugin().messages().send(player, "provideLink", "&cPodaj link do utworu.");
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
}
