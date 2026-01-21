package me.Luki.karaoke.listener;

import me.Luki.karaoke.Karaoke;
import me.Luki.karaoke.service.KaraokeService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class KaraokeLifecycleListener implements Listener {

    private final Karaoke plugin;
    private final KaraokeService service;

    public KaraokeLifecycleListener(Karaoke plugin, KaraokeService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        service.stop(event.getPlayer());
        plugin.debug().debug(() -> "Stopped session on quit for " + event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKick(PlayerKickEvent event) {
        service.stop(event.getPlayer());
        plugin.debug().debug(() -> "Stopped session on kick for " + event.getPlayer().getName());
    }
}
