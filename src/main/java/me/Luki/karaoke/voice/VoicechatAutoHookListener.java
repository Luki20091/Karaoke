package me.Luki.karaoke.voice;

import me.Luki.karaoke.Karaoke;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;

/**
 * If Simple Voice Chat loads after this plugin, automatically retry hooking.
 * Uses only Bukkit events so it is safe even when SVC isn't installed.
 */
public final class VoicechatAutoHookListener implements Listener {

    private final Karaoke plugin;

    public VoicechatAutoHookListener(Karaoke plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginEnable(PluginEnableEvent event) {
        if (event == null || event.getPlugin() == null) {
            return;
        }

        String name = event.getPlugin().getName();
        if (name == null || !name.equalsIgnoreCase("voicechat")) {
            return;
        }

        // Give SVC a tick to register its services.
        plugin.getServer().getScheduler().runTask(plugin, plugin::tryRegisterVoicechatIntegrationIfNeeded);
    }
}
