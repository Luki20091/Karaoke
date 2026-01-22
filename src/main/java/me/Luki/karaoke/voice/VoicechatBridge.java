package me.Luki.karaoke.voice;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.Entity;
import de.maxhenkel.voicechat.api.Position;
import de.maxhenkel.voicechat.api.ServerLevel;
import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioChannel;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import me.Luki.karaoke.Karaoke;
import me.Luki.karaoke.audio.FfmpegPcmSupplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Simple Voice Chat integration.
 *
 * IMPORTANT: This class references SVC API types and must only be loaded
 * when the voicechat plugin is present. The main plugin loads it via reflection.
 */
public final class VoicechatBridge implements VoicechatPlugin, VoiceBridge {

    private final Karaoke plugin;

    private volatile VoicechatServerApi serverApi;
    private volatile boolean logHook;

    private final Map<UUID, Playback> playbacks = new ConcurrentHashMap<>();

    public VoicechatBridge(Karaoke plugin) {
        this.plugin = plugin;
        reloadFromConfig();
    }

    @Override
    public void reloadFromConfig() {
        this.logHook = plugin.getConfig().getBoolean("svc.logHook", true);
    }

    @Override
    public boolean isHooked() {
        return serverApi != null;
    }

    public static void tryRegister(Karaoke plugin) {
        BukkitVoicechatService service = Bukkit.getServicesManager().load(BukkitVoicechatService.class);
        if (service == null) {
            plugin.getLogger().warning("Simple Voice Chat service not found. Voice features disabled.");
            return;
        }

        VoicechatBridge bridge = new VoicechatBridge(plugin);
        plugin.setVoiceBridge(bridge);
        service.registerPlugin(bridge);
    }

    @Override
    public String getPluginId() {
        // Must be unique and stable.
        return "karaoke";
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, this::onServerStarted);
    }

    private void onServerStarted(VoicechatServerStartedEvent event) {
        serverApi = event.getVoicechat();
        if (logHook) {
            plugin.getLogger().info("Simple Voice Chat API hooked");
        }
    }

    @Override
    public void startSessionAudio(UUID sessionId, Location origin, Path audioFile) {
        startSessionAudio(sessionId, (Player) null, origin, audioFile);
    }

    @Override
    public void startSessionAudio(UUID sessionId, Player contextPlayer, Location origin, Path audioFile) {
        startSessionAudio(sessionId, contextPlayer, origin, audioFile, 0L);
    }

    @Override
    public void startSessionAudio(UUID sessionId, Player contextPlayer, Location origin, Path audioFile, long startAtMillis) {
        if (sessionId == null || origin == null || origin.getWorld() == null) {
            return;
        }

        if (!plugin.isSvcEnabled()) {
            return;
        }

        VoicechatServerApi api = serverApi;
        if (api == null) {
            plugin.debug().debug(() -> "SVC enabled but not hooked yet; skipping audio start");
            return;
        }

        stopSessionAudio(sessionId);

        float distanceBlocks = (float) Math.max(1.0, plugin.getSvcDistanceBlocks());

        ServerLevel level = toServerLevel(api, contextPlayer, origin);
        if (level == null || level.getServerLevel() == null) {
            plugin.debug().warn("Failed to resolve SVC ServerLevel for world; skipping audio");
            return;
        }

        Position originPos = api.createPosition(origin.getX(), origin.getY(), origin.getZ());
        Object worldToken = level.getServerLevel();
        double maxDistSq = (double) distanceBlocks * (double) distanceBlocks;

        LocationalAudioChannel channel = api.createLocationalAudioChannel(sessionId, level, originPos);
        if (channel == null) {
            plugin.debug().warn("SVC returned null LocationalAudioChannel; skipping audio");
            return;
        }
        channel.setDistance(distanceBlocks);

        // Only hear within configured radius and in the same world.
        channel.setFilter((ServerPlayer player) -> isPlayerAllowed(player, worldToken, originPos, maxDistSq));

        OpusEncoder encoder = api.createEncoder();

        Supplier<short[]> supplier;
        FfmpegPcmSupplier ffmpegSupplier = null;
        boolean haveFile = audioFile != null;

        if (haveFile) {
            try {
                ffmpegSupplier = new FfmpegPcmSupplier(plugin, audioFile, Math.max(0L, startAtMillis));
                supplier = ffmpegSupplier;
            } catch (Throwable t) {
                supplier = null;
            }
        } else {
            supplier = null;
        }

        // Fallback to test tone if no file or ffmpeg streaming failed.
        if (supplier == null) {
            if (!plugin.getConfig().getBoolean("svc.testToneOnStart", true)) {
                return;
            }
            int durationMs = Math.max(100, plugin.getConfig().getInt("svc.testToneDurationMs", 700));
            double frequencyHz = Math.max(60.0, plugin.getConfig().getDouble("svc.testToneFrequencyHz", 440.0));
            double amplitude = clamp(plugin.getConfig().getDouble("svc.testToneAmplitude", 0.10), 0.0, 0.5);
            supplier = new ToneSupplier(durationMs, frequencyHz, amplitude);
        }

        AudioPlayer player = api.createAudioPlayer(channel, encoder, supplier);

        Playback playback = new Playback(channel, encoder, player, ffmpegSupplier);
        playbacks.put(sessionId, playback);

        player.setOnStopped(() -> {
            try {
                channel.flush();
            } catch (Throwable ignored) {
            }
            try {
                encoder.close();
            } catch (Throwable ignored) {
            }
            try {
                Playback p = playbacks.get(sessionId);
                if (p != null && p.ffmpegSupplier != null) {
                    p.ffmpegSupplier.close();
                }
            } catch (Throwable ignored) {
            }
            playbacks.remove(sessionId);
        });

        player.startPlaying();
    }

    @Override
    public void stopSessionAudio(UUID sessionId) {
        if (sessionId == null) {
            return;
        }
        Playback playback = playbacks.remove(sessionId);
        if (playback == null) {
            return;
        }

        try {
            playback.player.stopPlaying();
        } catch (Throwable ignored) {
        }

        try {
            if (playback.ffmpegSupplier != null) {
                playback.ffmpegSupplier.close();
            }
        } catch (Throwable ignored) {
        }

        try {
            playback.channel.flush();
        } catch (Throwable ignored) {
        }

        try {
            playback.encoder.close();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void shutdown() {
        for (UUID id : playbacks.keySet()) {
            stopSessionAudio(id);
        }
        playbacks.clear();
        serverApi = null;
    }

    private static boolean isPlayerAllowed(Entity player, Object worldToken, Position originPos, double maxDistSq) {
        if (!(player instanceof ServerPlayer sp) || sp.getServerLevel() == null) {
            return false;
        }
        try {
            ServerLevel level = sp.getServerLevel();
            if (level == null || level.getServerLevel() != worldToken) {
                return false;
            }
            Position p = sp.getPosition();
            if (p == null) {
                return false;
            }
            double dx = p.getX() - originPos.getX();
            double dy = p.getY() - originPos.getY();
            double dz = p.getZ() - originPos.getZ();
            return (dx * dx + dy * dy + dz * dz) <= maxDistSq;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private ServerLevel toServerLevel(VoicechatServerApi api, Player contextPlayer, Location origin) {
        // Bukkit Voicechat API expects Bukkit types here (World/Player), not NMS handles.
        try {
            var world = origin.getWorld();
            if (world != null) {
                ServerLevel level = api.fromServerLevel(world);
                if (level != null && level.getServerLevel() != null) {
                    return level;
                }
            }
        } catch (Throwable t) {
            if (logHook) {
                plugin.debug().debug(() -> "SVC world->level mapping threw: " + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
            }
        }

        // Fallback: resolve level from any Bukkit player in the target world.
        try {
            Player p = contextPlayer;
            if (origin.getWorld() != null && (p == null || p.getWorld() == null || !origin.getWorld().equals(p.getWorld()))) {
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (online != null && online.getWorld() != null && online.getWorld().equals(origin.getWorld())) {
                        p = online;
                        break;
                    }
                }
            }
            if (p == null) {
                return null;
            }
            ServerPlayer sp = api.fromServerPlayer(p);
            if (sp == null) {
                return null;
            }
            return sp.getServerLevel();
        } catch (Throwable t) {
            if (logHook) {
                plugin.debug().debug(() -> "SVC player->level mapping threw: " + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
            }
            return null;
        }
    }

    private record Playback(AudioChannel channel, OpusEncoder encoder, AudioPlayer player, FfmpegPcmSupplier ffmpegSupplier) {}

    private static final class ToneSupplier implements Supplier<short[]> {

        private static final int SAMPLE_RATE = 48_000;
        private static final int FRAME_SAMPLES = 960;
        private static final double FRAME_SECONDS = FRAME_SAMPLES / (double) SAMPLE_RATE;

        private int framesRemaining;
        private final double frequencyHz;
        private final double amplitude;
        private double phase;

        private ToneSupplier(int durationMs, double frequencyHz, double amplitude) {
            int frames = (int) Math.ceil(Math.max(1, durationMs) / (FRAME_SECONDS * 1000.0));
            this.framesRemaining = Math.max(1, frames);
            this.frequencyHz = frequencyHz;
            this.amplitude = amplitude;
            this.phase = 0.0;
        }

        @Override
        public short[] get() {
            if (framesRemaining <= 0) {
                return null;
            }
            framesRemaining--;

            short[] frame = new short[FRAME_SAMPLES];

            // Simple fade-in/out to avoid clicks
            double t0 = 1.0 - (framesRemaining / (double) Math.max(1, framesRemaining + 1));
            double fade = Math.min(1.0, Math.min(t0 * 8.0, framesRemaining * 0.08));
            double gain = amplitude * fade;

            double phaseStep = (2.0 * Math.PI * frequencyHz) / SAMPLE_RATE;
            for (int i = 0; i < FRAME_SAMPLES; i++) {
                double v = Math.sin(phase) * gain;
                frame[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, (int) Math.round(v * 32767.0)));
                phase += phaseStep;
                if (phase > (2.0 * Math.PI)) {
                    phase -= (2.0 * Math.PI);
                }
            }
            return frame;
        }
    }
}
