package me.Luki.karaoke;

import me.Luki.karaoke.command.KaraokeCommand;
import me.Luki.karaoke.command.KaraokeAdminCommand;
import me.Luki.karaoke.command.PlaylistCommand;
import me.Luki.karaoke.audio.FfmpegInstaller;
import me.Luki.karaoke.cache.MediaCache;
import me.Luki.karaoke.listener.FancyHologramsAutoHookListener;
import me.Luki.karaoke.listener.KaraokeLifecycleListener;
import me.Luki.karaoke.playlist.PlaylistStore;
import me.Luki.karaoke.service.KaraokeService;
import me.Luki.karaoke.util.DebugLogger;
import me.Luki.karaoke.util.Messages;
import me.Luki.karaoke.voice.VoiceBridge;
import me.Luki.karaoke.voice.VoicechatAutoHookListener;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collection;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;


public final class Karaoke extends JavaPlugin {

    private KaraokeService karaokeService;
    private PlaylistStore playlistStore;
    private MediaCache mediaCache;
    private DebugLogger debug;
    private Messages messages;
    private volatile VoiceBridge voiceBridge;
    private volatile boolean fancyHologramsReady;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // Optional: auto-install ffmpeg for SVC audio playback from cached mp3/ogg.
        FfmpegInstaller.ensureAvailable(this);

        this.messages = new Messages(this);

        boolean debugEnabled = getConfig().getBoolean("debug.enabled", false);
        boolean debugHttp = getConfig().getBoolean("debug.http", false);
        this.debug = new DebugLogger(getLogger(), debugEnabled, debugHttp);
        debug.debug(() -> "Debug logging enabled (http=" + debugHttp + ")");

        logFfmpegSourceOnce();

        if (getServer().getPluginManager().getPlugin("FancyHolograms") == null) {
            getLogger().severe("FancyHolograms plugin not found. Karaoke requires FancyHolograms 2.8.0 to run.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // FancyHolograms may enable slightly later (depending on server/plugin load order).
        // We keep Karaoke enabled and just block /karaoke until Fancy is ready.
        refreshFancyHologramsState();
        if (!fancyHologramsReady) {
            getLogger().warning("FancyHolograms plugin is installed but not enabled yet. Karaoke will wait and auto-hook when it becomes enabled.");
        }

        this.playlistStore = new PlaylistStore(this);
        this.playlistStore.load();

        this.mediaCache = new MediaCache(this);
        this.mediaCache.load();

        this.karaokeService = new KaraokeService(this, playlistStore);
        getServer().getPluginManager().registerEvents(new KaraokeLifecycleListener(this, karaokeService), this);
        getServer().getPluginManager().registerEvents(new FancyHologramsAutoHookListener(this), this);
        getServer().getPluginManager().registerEvents(new VoicechatAutoHookListener(this), this);

        registerPaperCommands();

        // Optional: hook SVC if present
        tryRegisterVoicechatIntegrationIfNeeded();

    }

    private void logFfmpegSourceOnce() {
        try {
            String configured = String.valueOf(getConfig().getString("audio.ffmpegPath", "ffmpeg")).trim();
            Path dataDir = getDataFolder().toPath().toAbsolutePath().normalize();
            Path bundled = dataDir.resolve("tools").resolve("ffmpeg").resolve(isWindows() ? "ffmpeg.exe" : "ffmpeg").normalize();

            String source;
            String pathToShow = configured;

            if (configured.equalsIgnoreCase("ffmpeg")) {
                boolean onPath = isFfmpegOnPath();
                source = onPath ? "PATH" : "MISSING";
            } else {
                Path p;
                try {
                    p = Path.of(configured).toAbsolutePath().normalize();
                } catch (Exception e) {
                    getLogger().info("ffmpeg: source=INVALID path='" + configured + "'");
                    return;
                }
                if (p.startsWith(dataDir) && p.equals(bundled)) {
                    source = "BUNDLED";
                } else {
                    source = "CONFIGURED";
                }
                pathToShow = p.toString();
                if (!Files.exists(p)) {
                    source = "MISSING";
                }
            }

            getLogger().info("ffmpeg: source=" + source + " path='" + pathToShow + "'");

            if ("MISSING".equals(source) && isSvcEnabled()) {
                getLogger().warning("SVC audio is enabled but ffmpeg is missing. Karaoke audio will not play until ffmpeg is installed.");
                getLogger().warning("Set audio.autoDownloadFfmpeg=true and audio.ffmpegDownloadUrl (or audio.ffmpegDownloadUrlWindows/Linux), or set audio.ffmpegPath to a valid binary.");
            }
        } catch (Exception ignored) {
        }
    }

    private boolean isWindows() {
        String os = String.valueOf(System.getProperty("os.name", "")).toLowerCase(java.util.Locale.ROOT);
        return os.contains("win");
    }

    private boolean isFfmpegOnPath() {
        try {
            Process p = new ProcessBuilder(java.util.List.of("ffmpeg", "-version"))
                    .redirectErrorStream(true)
                    .start();
            boolean ok = p.waitFor() == 0;
            try {
                p.destroy();
            } catch (Exception ignored) {
            }
            return ok;
        } catch (Exception e) {
            return false;
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

        if (playlistStore != null) {
            try {
                playlistStore.save();
            } catch (Exception e) {
                getLogger().warning("Failed to save playlists.json on disable: " + e.getMessage());
            }
        }

        if (mediaCache != null) {
            try {
                mediaCache.save();
            } catch (Exception e) {
                getLogger().warning("Failed to save cache index on disable: " + e.getMessage());
            }
        }

        VoiceBridge bridge = voiceBridge;
        if (bridge != null) {
            try {
                bridge.shutdown();
            } catch (Exception e) {
                getLogger().warning("Failed to shutdown voice bridge cleanly: " + e.getMessage());
            }
        }
    }

    public DebugLogger debug() {
        return debug;
    }

    public Messages messages() {
        return messages;
    }

    public PlaylistStore playlistStore() {
        return playlistStore;
    }

    public MediaCache mediaCache() {
        return mediaCache;
    }

    public boolean isFancyHologramsReady() {
        return fancyHologramsReady;
    }

    public void refreshFancyHologramsState() {
        boolean enabled = getServer().getPluginManager().isPluginEnabled("FancyHolograms");
        boolean changed = enabled != fancyHologramsReady;
        fancyHologramsReady = enabled;
        if (changed && enabled) {
            getLogger().info("FancyHolograms is enabled; Karaoke holograms are now available.");
        }
    }

    public void setVoiceBridge(VoiceBridge voiceBridge) {
        this.voiceBridge = voiceBridge;
    }

    public VoiceBridge voiceBridge() {
        return voiceBridge;
    }

    public boolean isSvcHooked() {
        VoiceBridge bridge = voiceBridge;
        try {
            return bridge != null && bridge.isHooked();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public boolean isSvcEnabled() {
        // Prefer the WalkieTalkie-style keys, fall back to legacy voicechat.*
        if (getConfig().contains("svc.enabled")) {
            return getConfig().getBoolean("svc.enabled", true);
        }
        return getConfig().getBoolean("voicechat.enabled", true);
    }

    public double getSvcDistanceBlocks() {
        if (getConfig().contains("svc.distanceBlocks")) {
            return Math.max(0D, getConfig().getDouble("svc.distanceBlocks", 50D));
        }
        return Math.max(0D, getConfig().getDouble("voicechat.distance", 50D));
    }

    public String getSvcHost() {
        String host = String.valueOf(getConfig().getString("svc.host", "127.0.0.1")).trim();
        if (!host.isBlank()) {
            return host;
        }
        String addr = String.valueOf(getConfig().getString("svc.address", "")).trim();
        if (addr.contains(":")) {
            return addr.substring(0, addr.indexOf(':')).trim();
        }
        return addr.isBlank() ? "127.0.0.1" : addr;
    }

    public int getSvcPort() {
        // svc.port takes precedence
        if (getConfig().contains("svc.port")) {
            return Math.max(1, getConfig().getInt("svc.port", 24454));
        }
        // legacy: try parse svc.address
        String addr = String.valueOf(getConfig().getString("svc.address", "")).trim();
        if (addr.contains(":")) {
            String portPart = addr.substring(addr.indexOf(':') + 1).trim();
            try {
                int p = Integer.parseInt(portPart);
                return Math.max(1, p);
            } catch (Exception ignored) {
            }
        }
        return 24454;
    }

    public String getSvcAddress() {
        String host = getSvcHost();
        int port = getSvcPort();
        return host + ":" + port;
    }

    public void tryRegisterVoicechatIntegrationIfNeeded() {
        VoiceBridge existing = voiceBridge;
        if (existing != null && existing.isHooked()) {
            return;
        }

        if (!isSvcEnabled()) {
            getLogger().info("SVC integration disabled in config (svc.enabled=false). Voice features disabled.");
            return;
        }

        var pm = getServer().getPluginManager();
        var svc = pm.getPlugin("voicechat");
        if (svc == null) {
            // Optional integration: don't spam warnings if SVC isn't installed.
            debug.debug("Simple Voice Chat plugin not installed; voice features disabled.");
            return;
        }

        if (!pm.isPluginEnabled("voicechat")) {
            // Hook when it becomes enabled (listener will call this again).
            debug.debug("Simple Voice Chat is installed but not enabled yet; waiting for enable to hook.");
            return;
        }

        try {
            Class<?> bridgeClass = Class.forName("me.Luki.karaoke.voice.VoicechatBridge", true, getClassLoader());
            bridgeClass.getMethod("tryRegister", Karaoke.class).invoke(null, this);
        } catch (Throwable t) {
            getLogger().severe("Failed to hook Simple Voice Chat. Voice features disabled.");
            t.printStackTrace();
        }
    }

    private void registerPaperCommands() {
        final KaraokeCommand karaokeCmd = new KaraokeCommand(karaokeService);
        final KaraokeAdminCommand adminCmd = new KaraokeAdminCommand(this, karaokeService);
        final PlaylistCommand playlistCmd = new PlaylistCommand(this, playlistStore);

        this.registerCommand(
                "karaoke",
                "Start karaoke from a link (YouTube/Spotify/etc)",
                List.of(),
                new BasicCommand() {
                    @Override
                    public void execute(CommandSourceStack commandSourceStack, String[] args) {
                        karaokeCmd.execute(commandSourceStack.getSender(), "karaoke", args);
                    }

                    @Override
                    public Collection<String> suggest(CommandSourceStack commandSourceStack, String[] args) {
                        return karaokeCmd.suggest(commandSourceStack.getSender(), args);
                    }

                    @Override
                    public String permission() {
                        return "karaoke.use";
                    }
                }
        );

        this.registerCommand(
                "karaokeadmin",
                "Admin commands for Karaoke",
                List.of(),
                new BasicCommand() {
                    @Override
                    public void execute(CommandSourceStack commandSourceStack, String[] args) {
                        adminCmd.execute(commandSourceStack.getSender(), "karaokeadmin", args);
                    }

                    @Override
                    public Collection<String> suggest(CommandSourceStack commandSourceStack, String[] args) {
                        return adminCmd.suggest(commandSourceStack.getSender(), args);
                    }

                    @Override
                    public String permission() {
                        return "karaoke.admin";
                    }
                }
        );

        this.registerCommand(
                "playlist",
                "Manage Karaoke playlists",
                List.of(),
                new BasicCommand() {
                    @Override
                    public void execute(CommandSourceStack commandSourceStack, String[] args) {
                        playlistCmd.execute(commandSourceStack.getSender(), "playlist", args);
                    }

                    @Override
                    public Collection<String> suggest(CommandSourceStack commandSourceStack, String[] args) {
                        return playlistCmd.suggest(commandSourceStack.getSender(), args);
                    }

                    @Override
                    public String permission() {
                        return "karaoke.playlist";
                    }
                }
        );
    }
}
