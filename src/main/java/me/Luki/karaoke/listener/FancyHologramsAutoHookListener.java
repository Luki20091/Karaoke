package me.Luki.karaoke.listener;

import me.Luki.karaoke.Karaoke;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;

/**
 * If FancyHolograms loads after this plugin, automatically retry hooking.
 *
 * This avoids disabling Karaoke just because FancyHolograms wasn't enabled yet
 * at the time of our onEnable().
 */
public final class FancyHologramsAutoHookListener implements Listener {

    private final Karaoke plugin;

    public FancyHologramsAutoHookListener(Karaoke plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginEnable(PluginEnableEvent event) {
        if (event == null || event.getPlugin() == null) {
            return;
        }

        String name = event.getPlugin().getName();
        if (name == null || !name.equalsIgnoreCase("FancyHolograms")) {
            return;
        }

        // Mark as ready on the next tick to ensure FancyHolograms finished enabling.
        plugin.getServer().getScheduler().runTask(plugin, plugin::refreshFancyHologramsState);
    }
}
