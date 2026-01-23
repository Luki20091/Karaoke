package me.Luki.karaoke.util;

import me.Luki.karaoke.Karaoke;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.atomic.AtomicLong;

public final class ActionBarProgress implements AutoCloseable {

    private final Karaoke plugin;
    private final Player player;
    private final AtomicLong bytesRead;
    private final AtomicLong totalBytes;
    private final AtomicLong lastUpdateMillis;

    private volatile String stage;
    private volatile boolean closed;
    private BukkitTask task;

    private ActionBarProgress(Karaoke plugin, Player player, String stage) {
        this.plugin = plugin;
        this.player = player;
        this.stage = stage == null ? "" : stage;
        this.bytesRead = new AtomicLong(0L);
        this.totalBytes = new AtomicLong(-1L);
        this.lastUpdateMillis = new AtomicLong(System.currentTimeMillis());
        this.closed = false;
    }

    public static ActionBarProgress start(Karaoke plugin, Player player, String stage) {
        if (plugin == null || player == null) {
            return null;
        }
        boolean enabled = plugin.getConfig().getBoolean("ui.actionbar.enabled", true);
        if (!enabled) {
            return null;
        }

        ActionBarProgress p = new ActionBarProgress(plugin, player, stage);
        int periodTicks = Math.max(1, plugin.getConfig().getInt("ui.actionbar.updatePeriodTicks", 4));
        p.task = plugin.getServer().getScheduler().runTaskTimer(plugin, p::tick, 1L, periodTicks);
        return p;
    }

    public void setStage(String stage) {
        if (closed) {
            return;
        }
        this.stage = stage == null ? "" : stage;
        lastUpdateMillis.set(System.currentTimeMillis());
    }

    public void update(long bytesRead, long totalBytes) {
        if (closed) {
            return;
        }
        this.bytesRead.set(Math.max(0L, bytesRead));
        this.totalBytes.set(totalBytes);
        lastUpdateMillis.set(System.currentTimeMillis());
    }

    public ProgressListener asListener() {
        return this::update;
    }

    private void tick() {
        if (closed) {
            return;
        }
        if (player == null || !player.isOnline()) {
            close();
            return;
        }

        long now = System.currentTimeMillis();
        int timeoutSeconds = Math.max(3, plugin.getConfig().getInt("ui.actionbar.timeoutSeconds", 15));
        long last = lastUpdateMillis.get();
        if ((now - last) > timeoutSeconds * 1000L) {
            close();
            return;
        }

        long read = bytesRead.get();
        long total = totalBytes.get();

        int width = Math.max(5, plugin.getConfig().getInt("ui.actionbar.barWidth", 20));
        int percent;
        if (total > 0L) {
            percent = (int) Math.max(0L, Math.min(100L, (read * 100L) / total));
        } else {
            // Unknown total: keep it "indeterminate" and show 0..100 looping.
            percent = (int) ((now / 250L) % 101L);
        }

        int filled = (int) Math.round((percent / 100.0) * width);
        filled = Math.max(0, Math.min(width, filled));

        String bar = "&a" + "█".repeat(filled) + "&7" + "░".repeat(width - filled);
        String stageText = stage == null ? "" : stage;

        Component msg = plugin.messages().format(
                plugin.getConfig().getString(
                        "messages.actionbarProgress",
                        "&7[{bar}&7] &f{percent}% &7{stage}"
                ),
                "bar", bar,
                "percent", String.valueOf(percent),
                "stage", stageText,
                "read", String.valueOf(read),
                "total", String.valueOf(total)
        );

        try {
            player.sendActionBar(msg);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        if (task != null) {
            try {
                task.cancel();
            } catch (Exception ignored) {
            }
            task = null;
        }

        if (player != null && player.isOnline()) {
            try {
                player.sendActionBar(Component.empty());
            } catch (Throwable ignored) {
            }
        }
    }
}
