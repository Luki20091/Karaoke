package me.Luki.karaoke.audio;

import me.Luki.karaoke.Karaoke;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.tukaani.xz.XZInputStream;

public final class FfmpegInstaller {

    private FfmpegInstaller() {
    }

    private static void dbg(Karaoke plugin, String message) {
        if (plugin == null) {
            return;
        }
        try {
            plugin.debug().debug(() -> "ffmpeg: " + message);
        } catch (Throwable ignored) {
            // Keep installer safe even if debug logger isn't initialized yet.
        }
    }

    public static void ensureAvailable(Karaoke plugin) {
        if (plugin == null) {
            return;
        }

        boolean enabled = plugin.getConfig().getBoolean("audio.autoDownloadFfmpeg", false);
        if (!enabled) {
            return;
        }

        String os = String.valueOf(System.getProperty("os.name", "")).toLowerCase(Locale.ROOT);
        String arch = String.valueOf(System.getProperty("os.arch", "")).toLowerCase(Locale.ROOT);
        boolean windows = os.contains("win");
        boolean linux = os.contains("linux");
        boolean mac = os.contains("mac") || os.contains("darwin");

        dbg(plugin, "autoDownload enabled; os.name='" + System.getProperty("os.name", "") + "' os.arch='" + System.getProperty("os.arch", "") + "' java='" + System.getProperty("java.version", "") + "'");

        String configured = String.valueOf(plugin.getConfig().getString("audio.ffmpegPath", "ffmpeg")).trim();
        dbg(plugin, "configured audio.ffmpegPath='" + configured + "'");
        if (isRunnableFfmpeg(configured)) {
            dbg(plugin, "configured ffmpeg path exists; skipping auto-download");
            return;
        }

        // If configured as "ffmpeg" and it's on PATH, skip download.
        if (configured.equalsIgnoreCase("ffmpeg") && isOnPath()) {
            dbg(plugin, "ffmpeg is on PATH; skipping auto-download");
            return;
        }

        if (configured.equalsIgnoreCase("ffmpeg")) {
            dbg(plugin, "ffmpeg not on PATH or not runnable; will attempt to download");
        }

        Path installDir = plugin.getDataFolder().toPath().resolve("tools").resolve("ffmpeg");
        Path ffmpegExe = installDir.resolve(windows ? "ffmpeg.exe" : "ffmpeg");
        dbg(plugin, "installDir='" + installDir.toAbsolutePath() + "' expectedBinary='" + ffmpegExe.toAbsolutePath() + "'");
        if (Files.exists(ffmpegExe)) {
            plugin.getConfig().set("audio.ffmpegPath", ffmpegExe.toAbsolutePath().toString());
            plugin.saveConfig();
            plugin.getLogger().info("Using bundled ffmpeg: " + ffmpegExe.toAbsolutePath());
            dbg(plugin, "bundled ffmpeg already exists; updated config audio.ffmpegPath");
            return;
        }

        String url = String.valueOf(plugin.getConfig().getString("audio.ffmpegDownloadUrl", "")).trim();
        if (url.isBlank()) {
            url = defaultUrlFor(os);
            if (url.isBlank()) {
                plugin.getLogger().warning("audio.autoDownloadFfmpeg=true but no default ffmpeg URL is available for this OS. Set audio.ffmpegDownloadUrl.");
                return;
            }
            plugin.getConfig().set("audio.ffmpegDownloadUrl", url);
            plugin.saveConfig();
            dbg(plugin, "audio.ffmpegDownloadUrl was blank; wrote default='" + url + "'");
        }

        // If running on Linux but URL points to a Windows build, auto-correct.
        if (linux && url.toLowerCase(Locale.ROOT).contains("win")) {
            String corrected = defaultUrlFor(os);
            if (!corrected.isBlank()) {
                plugin.getLogger().warning("audio.ffmpegDownloadUrl appears to be a Windows build on Linux; switching to a Linux build URL.");
                url = corrected;
                plugin.getConfig().set("audio.ffmpegDownloadUrl", url);
                plugin.saveConfig();
                dbg(plugin, "auto-corrected Windows URL to Linux default='" + url + "'");
            }
        }

        // If running on Windows but URL points to a Linux tar.* build, auto-correct.
        String lower = url.toLowerCase(Locale.ROOT);
        if (windows && (lower.contains("johnvansickle") || lower.endsWith(".tar.xz") || lower.endsWith(".tar.gz") || lower.endsWith(".tar"))) {
            String corrected = defaultUrlFor(os);
            if (!corrected.isBlank()) {
                plugin.getLogger().warning("audio.ffmpegDownloadUrl appears to be a Linux tar.* build on Windows; switching to a Windows build URL.");
                url = corrected;
                plugin.getConfig().set("audio.ffmpegDownloadUrl", url);
                plugin.saveConfig();
                dbg(plugin, "auto-corrected Linux URL to Windows default='" + url + "'");
            }
        }

        if (mac && url.toLowerCase(Locale.ROOT).contains("win")) {
            plugin.getLogger().warning("audio.ffmpegDownloadUrl looks like a Windows build. Please set a macOS ffmpeg URL or install ffmpeg via your system package manager.");
        }

        dbg(plugin, "selected download URL='" + url + "' (windows=" + windows + " linux=" + linux + " mac=" + mac + " arch=" + arch + ")");

        plugin.getLogger().warning("Downloading ffmpeg (3rd-party binary). By using audio.autoDownloadFfmpeg you accept the ffmpeg licensing terms.");
        plugin.getLogger().info("Downloading ffmpeg from: " + url);

        try {
            downloadAndInstallFfmpeg(plugin, url, installDir, windows);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to auto-download ffmpeg: " + e.getMessage());
            dbg(plugin, "download/install failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }

        if (Files.exists(ffmpegExe)) {
            if (!windows) {
                try {
                    setExecutable(ffmpegExe);
                } catch (Exception ignored) {
                }
            }
            plugin.getConfig().set("audio.ffmpegPath", ffmpegExe.toAbsolutePath().toString());
            plugin.saveConfig();
            plugin.getLogger().info("ffmpeg installed to: " + ffmpegExe.toAbsolutePath());
            dbg(plugin, "install OK; set audio.ffmpegPath='" + ffmpegExe.toAbsolutePath() + "'");
        } else {
            plugin.getLogger().warning("ffmpeg download finished, but ffmpeg binary was not found in extracted files.");
            dbg(plugin, "install finished but binary missing at expected path: '" + ffmpegExe.toAbsolutePath() + "'");
        }
    }

    private static boolean isRunnableFfmpeg(String configured) {
        if (configured == null || configured.isBlank()) {
            return false;
        }
        String v = configured.trim();
        if (v.equalsIgnoreCase("ffmpeg")) {
            return false;
        }
        Path p = Path.of(v);
        return Files.exists(p);
    }

    private static boolean isOnPath() {
        try {
            Process p = new ProcessBuilder(List.of("ffmpeg", "-version"))
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

    private static void downloadAndInstallFfmpeg(Karaoke plugin, String url, Path installDir, boolean windows) throws Exception {
        Files.createDirectories(installDir);

        long t0 = System.nanoTime();

        int timeoutSeconds = Math.max(5, plugin.getConfig().getInt("audio.ffmpegDownloadTimeoutSeconds", 120));
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        String lowerUrl = url.toLowerCase(Locale.ROOT);
        boolean isZip = lowerUrl.endsWith(".zip");
        boolean isTarXz = lowerUrl.endsWith(".tar.xz");
        boolean isTarGz = lowerUrl.endsWith(".tar.gz");
        boolean isTar = lowerUrl.endsWith(".tar");

        String kind = isZip ? "zip" : (isTarXz ? "tar.xz" : (isTarGz ? "tar.gz" : (isTar ? "tar" : "binary")));
        dbg(plugin, "download kind=" + kind + " timeoutSeconds=" + timeoutSeconds);

        Path downloadPath = installDir.resolve((isZip || isTarXz || isTarGz || isTar) ? "ffmpeg.archive" : (windows ? "ffmpeg.exe" : "ffmpeg"));
        Path tmp = installDir.resolve(downloadPath.getFileName().toString() + ".part");

        dbg(plugin, "downloading to tmp='" + tmp.toAbsolutePath() + "' final='" + downloadPath.toAbsolutePath() + "'");

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("User-Agent", "KaraokePlugin/1.0")
                .GET()
                .build();

        HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + res.statusCode());
        }

        res.headers().firstValue("Content-Length").ifPresent(cl -> dbg(plugin, "HTTP 200 content-length=" + cl));

        try (InputStream in = res.body()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, downloadPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
        }

        try {
            dbg(plugin, "download complete bytes=" + Files.size(downloadPath));
        } catch (IOException ignored) {
        }

        long tDownload = System.nanoTime();

        if (isZip) {
            dbg(plugin, "extracting zip");
            extractFromZip(downloadPath, installDir, windows);
            try {
                Files.deleteIfExists(downloadPath);
            } catch (IOException ignored) {
            }
            long tEnd = System.nanoTime();
            dbg(plugin, "extract OK (downloadMs=" + ((tDownload - t0) / 1_000_000L) + " extractMs=" + ((tEnd - tDownload) / 1_000_000L) + ")");
            return;
        }

        if (isTarXz || isTarGz || isTar) {
            dbg(plugin, "extracting tar (xz=" + isTarXz + " gz=" + isTarGz + ")");
            extractFromTar(downloadPath, installDir, windows, isTarXz, isTarGz);
            try {
                Files.deleteIfExists(downloadPath);
            } catch (IOException ignored) {
            }
            long tEnd = System.nanoTime();
            dbg(plugin, "extract OK (downloadMs=" + ((tDownload - t0) / 1_000_000L) + " extractMs=" + ((tEnd - tDownload) / 1_000_000L) + ")");
        }
    }

    private static void extractFromZip(Path zipPath, Path installDir, boolean windows) throws IOException {
        String wanted = windows ? "ffmpeg.exe" : "ffmpeg";
        boolean extracted = false;
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                String name = e.getName().replace('\\', '/');
                String lower = name.toLowerCase(Locale.ROOT);
                if (!lower.endsWith("/" + wanted) && !lower.endsWith("/bin/" + wanted) && !lower.endsWith(wanted)) {
                    continue;
                }
                Path out = installDir.resolve(wanted);
                Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                extracted = true;
                break;
            }
        }

        if (!extracted) {
            throw new IllegalStateException("Archive did not contain " + wanted);
        }
    }

    private static void extractFromTar(Path archive, Path installDir, boolean windows, boolean xz, boolean gz) throws IOException {
        String wanted = windows ? "ffmpeg.exe" : "ffmpeg";

        try (InputStream raw = Files.newInputStream(archive);
             InputStream decoded = xz ? new XZInputStream(raw) : (gz ? new GZIPInputStream(raw) : raw)) {

            boolean extracted = false;
            byte[] header = new byte[512];

            while (true) {
                int n = readFully(decoded, header, 0, 512);
                if (n == 0) {
                    break;
                }
                if (n < 512) {
                    throw new IOException("Truncated tar header");
                }

                boolean allZero = true;
                for (byte b : header) {
                    if (b != 0) {
                        allZero = false;
                        break;
                    }
                }
                if (allZero) {
                    break;
                }

                String name = tarString(header, 0, 100).replace('\\', '/');
                long size = tarOctal(header, 124, 12);
                char type = (char) header[156];

                boolean isFile = type == 0 || type == '0';
                String lower = name.toLowerCase(Locale.ROOT);

                if (isFile && (lower.endsWith("/" + wanted) || lower.endsWith("/bin/" + wanted) || lower.endsWith(wanted))) {
                    Path out = installDir.resolve(wanted);
                    Files.createDirectories(out.getParent());
                    try (OutputStream os = Files.newOutputStream(out)) {
                        copyExactly(decoded, os, size);
                    }
                    extracted = true;
                } else {
                    skipExactly(decoded, size);
                }

                // tar pads to 512-byte blocks
                long pad = (512 - (size % 512)) % 512;
                if (pad > 0) {
                    skipExactly(decoded, pad);
                }

                if (extracted) {
                    break;
                }
            }

            if (!extracted) {
                throw new IOException("Archive did not contain " + wanted);
            }
        }
    }

    private static int readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int r = in.read(buf, off + total, len - total);
            if (r == -1) {
                break;
            }
            total += r;
        }
        return total;
    }

    private static void copyExactly(InputStream in, OutputStream out, long bytes) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long remaining = bytes;
        while (remaining > 0) {
            int toRead = (int) Math.min(buf.length, remaining);
            int r = in.read(buf, 0, toRead);
            if (r == -1) {
                throw new IOException("Unexpected EOF");
            }
            out.write(buf, 0, r);
            remaining -= r;
        }
    }

    private static void skipExactly(InputStream in, long bytes) throws IOException {
        long remaining = bytes;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() == -1) {
                    throw new IOException("Unexpected EOF");
                }
                remaining -= 1;
            } else {
                remaining -= skipped;
            }
        }
    }

    private static String tarString(byte[] header, int off, int len) {
        int end = off;
        int max = off + len;
        while (end < max && header[end] != 0) {
            end++;
        }
        return new String(header, off, end - off, java.nio.charset.StandardCharsets.US_ASCII).trim();
    }

    private static long tarOctal(byte[] header, int off, int len) {
        String s = tarString(header, off, len).trim();
        if (s.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(s, 8);
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String defaultUrlFor(String osNameLower) {
        String os = Objects.requireNonNullElse(osNameLower, "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "https://github.com/BtbN/FFmpeg-Builds/releases/latest/download/ffmpeg-master-latest-win64-gpl.zip";
        }
        if (os.contains("linux")) {
            // Ubuntu 24.04 amd64 friendly static build (tar.xz)
            return "https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz";
        }
        // macOS: no default (varies widely). Recommend system package manager.
        return "";
    }

    private static void setExecutable(Path file) throws IOException {
        try {
            var perms = Files.getPosixFilePermissions(file);
            perms.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
            perms.add(java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE);
            perms.add(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX FS (e.g., Windows)
        }
    }
}
