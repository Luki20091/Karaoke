package me.Luki.karaoke.voice;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.nio.file.Path;

import java.util.UUID;

/**
 * Minimal abstraction for optional voice integrations.
 *
 * This interface must not reference Simple Voice Chat API types,
 * so the plugin can load even when SVC is not installed.
 */
public interface VoiceBridge {

    void reloadFromConfig();

    boolean isHooked();

    default void startSessionAudio(UUID sessionId, Location origin) {
        startSessionAudio(sessionId, origin, null);
    }

    /**
     * Starts locational audio near origin. If a player is provided, implementations may use it
     * as a context to resolve world/level handles more reliably.
     */
    default void startSessionAudio(UUID sessionId, Player player, Location origin, Path audioFile) {
        startSessionAudio(sessionId, origin, audioFile);
    }

    /**
     * Starts locational audio near origin at a given offset (milliseconds) into the track.
     * Implementations may ignore the offset if unsupported.
     */
    default void startSessionAudio(UUID sessionId, Player player, Location origin, Path audioFile, long startAtMillis) {
        startSessionAudio(sessionId, player, origin, audioFile);
    }

    void startSessionAudio(UUID sessionId, Location origin, Path audioFile);

    void stopSessionAudio(UUID sessionId);

    void shutdown();
}
