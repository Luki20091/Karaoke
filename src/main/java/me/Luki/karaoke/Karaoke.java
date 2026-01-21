package me.Luki.karaoke;

import me.Luki.karaoke.command.KaraokeCommand;
import me.Luki.karaoke.command.KaraokeAdminCommand;
import me.Luki.karaoke.listener.KaraokeLifecycleListener;
import me.Luki.karaoke.service.KaraokeService;
import me.Luki.karaoke.util.DebugLogger;
import me.Luki.karaoke.util.Messages;
import org.bukkit.plugin.java.JavaPlugin;

public final class Karaoke extends JavaPlugin {

    private KaraokeService karaokeService;
    private DebugLogger debug;
    private Messages messages;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.messages = new Messages(this);

        boolean debugEnabled = getConfig().getBoolean("debug.enabled", false);
        boolean debugHttp = getConfig().getBoolean("debug.http", false);
        this.debug = new DebugLogger(getLogger(), debugEnabled, debugHttp);
        debug.debug(() -> "Debug logging enabled (http=" + debugHttp + ")");

        if (getServer().getPluginManager().getPlugin("FancyHolograms") == null) {
            getLogger().severe("FancyHolograms plugin not found. Karaoke requires FancyHolograms 2.8.0 to run.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (!getServer().getPluginManager().isPluginEnabled("FancyHolograms")) {
            getLogger().severe("FancyHolograms plugin is installed but not enabled. Karaoke cannot start.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.karaokeService = new KaraokeService(this);
        getServer().getPluginManager().registerEvents(new KaraokeLifecycleListener(this, karaokeService), this);

        if (getCommand("karaoke") != null) {
            getCommand("karaoke").setExecutor(new KaraokeCommand(karaokeService));
        } else {
            getLogger().warning("Command /karaoke is not registered. Check paper-plugin.yml");
        }

        if (getCommand("karaokeadmin") != null) {
            getCommand("karaokeadmin").setExecutor(new KaraokeAdminCommand(this, karaokeService));
        } else {
            getLogger().warning("Command /karaokeadmin is not registered. Check paper-plugin.yml");
        }

    }

    @Override
    public void onDisable() {
        if (debug != null) {
            debug.debug("Plugin disabling - stopping all sessions.");
        }
        if (karaokeService != null) {
            try {
                karaokeService.stopAll();
            } catch (Exception e) {
                getLogger().warning("Failed to stop all sessions cleanly on disable: " + e.getMessage());
            }
        }
    }

    public DebugLogger debug() {
        return debug;
    }

    public Messages messages() {
        return messages;
    }
}
