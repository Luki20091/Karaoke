package me.Luki.karaoke.util;

@FunctionalInterface
public interface ProgressListener {

    /**
     * @param bytesRead  bytes read so far (>= 0)
     * @param totalBytes total bytes if known, otherwise -1
     */
    void onProgress(long bytesRead, long totalBytes);
}
