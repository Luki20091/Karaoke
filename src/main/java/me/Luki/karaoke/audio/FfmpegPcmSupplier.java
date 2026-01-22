package me.Luki.karaoke.audio;

import me.Luki.karaoke.Karaoke;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Streams decoded PCM (s16le) frames from ffmpeg.
 *
 * Requires ffmpeg to be installed or configured.
 */
public final class FfmpegPcmSupplier implements Supplier<short[]> {

    private static final int SAMPLE_RATE = 48_000;
    private static final int FRAME_SAMPLES = 960;

    private final Karaoke plugin;
    private final Path input;
    private final String ffmpeg;
    private final long startOffsetMs;

    private Process process;
    private InputStream stdout;
    private boolean finished;

    public FfmpegPcmSupplier(Karaoke plugin, Path input) {
        this(plugin, input, 0L);
    }

    public FfmpegPcmSupplier(Karaoke plugin, Path input, long startOffsetMs) {
        this.plugin = Objects.requireNonNull(plugin);
        this.input = Objects.requireNonNull(input);
        this.ffmpeg = String.valueOf(plugin.getConfig().getString("audio.ffmpegPath", "ffmpeg")).trim();
        this.startOffsetMs = Math.max(0L, startOffsetMs);
    }

    @Override
    public short[] get() {
        if (finished) {
            return null;
        }

        try {
            ensureStarted();

            int bytesPerFrame = FRAME_SAMPLES * 2; // mono s16le
            byte[] buf = stdout.readNBytes(bytesPerFrame);
            if (buf.length < bytesPerFrame) {
                finished = true;
                close();
                return null;
            }

            short[] frame = new short[FRAME_SAMPLES];
            for (int i = 0; i < FRAME_SAMPLES; i++) {
                int lo = buf[i * 2] & 0xFF;
                int hi = buf[i * 2 + 1];
                frame[i] = (short) ((hi << 8) | lo);
            }
            return frame;
        } catch (Exception e) {
            finished = true;
            close();
            return null;
        }
    }

    private void ensureStarted() throws IOException {
        if (process != null) {
            return;
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpeg);
        cmd.add("-hide_banner");
        cmd.add("-loglevel");
        cmd.add("error");

        // Seek before input for fast seek where supported.
        if (startOffsetMs > 0L) {
            cmd.add("-ss");
            cmd.add(String.format(java.util.Locale.US, "%.3f", startOffsetMs / 1000.0));
        }
        cmd.add("-i");
        cmd.add(input.toAbsolutePath().toString());
        cmd.add("-f");
        cmd.add("s16le");
        cmd.add("-ac");
        cmd.add("1");
        cmd.add("-ar");
        cmd.add(String.valueOf(SAMPLE_RATE));
        cmd.add("pipe:1");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        try {
            process = pb.start();
            stdout = new BufferedInputStream(process.getInputStream(), 1024 * 64);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to start ffmpeg ('" + ffmpeg + "'): " + e.getMessage());
            throw e;
        }
    }

    public void close() {
        try {
            if (stdout != null) {
                stdout.close();
            }
        } catch (Exception ignored) {
        }
        stdout = null;

        if (process != null) {
            try {
                process.destroy();
            } catch (Exception ignored) {
            }
            try {
                process.destroyForcibly();
            } catch (Exception ignored) {
            }
        }
        process = null;
    }
}
