package me.Luki.karaoke.hologram;

import de.oliver.fancyholograms.api.HologramManager;
import de.oliver.fancyholograms.api.FancyHologramsPlugin;
import de.oliver.fancyholograms.api.data.TextHologramData;
import de.oliver.fancyholograms.api.hologram.Hologram;
import me.Luki.karaoke.Karaoke;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class KaraokeHologram implements AutoCloseable {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final Karaoke plugin;
    private final HologramManager manager;
    private final List<Hologram> holograms;
    private final List<String> hologramNames;
    private final String[] lastLines;

    private static final int LYRICS_BOTTOM = 0;
    private static final int LYRICS_MIDDLE = 1;
    private static final int LYRICS_TOP = 2;
    private static final int HEADER_EVENT = 3;
    private static final int HEADER_NOW_PLAYING = 4;

    public KaraokeHologram(Karaoke plugin, KaraokePlacement placement) {
        this.plugin = plugin;
        this.manager = FancyHologramsPlugin.get().getHologramManager();
        this.holograms = new ArrayList<>(5);
        this.hologramNames = new ArrayList<>(5);
        this.lastLines = new String[] { null, null, null, null, null };

        Location base = placement.baseLocation().clone();
        double lineSpacing = placement.lineSpacing();
        double headerSpacing = placement.headerSpacing();

        // Unique prefix so multiple players can run karaoke at the same time
        String prefix = "karaoke_" + UUID.randomUUID() + "_";

        // 5 lines total:
        // - 3 lyrics lines (bottom/middle/top)
        // - 2 header lines above lyrics, with extra gap (headerSpacing) between header and lyrics
        double[] yOffsets = new double[] {
                0D,
                1D * lineSpacing,
                2D * lineSpacing,
                2D * lineSpacing + headerSpacing,
                2D * lineSpacing + headerSpacing + lineSpacing
        };

        for (int i = 0; i < yOffsets.length; i++) {
            Location lineLoc = base.clone().add(0D, yOffsets[i], 0D);
            String name = prefix + i;

            TextHologramData data = new TextHologramData(name, lineLoc);
            data.setPersistent(false);
            data.setSeeThrough(true);
            data.setTextAlignment(org.bukkit.entity.TextDisplay.TextAlignment.CENTER);
            data.setBillboard(org.bukkit.entity.Display.Billboard.FIXED);
            data.setText(List.of(""));

            Hologram hologram = manager.create(data);
            manager.addHologram(hologram);
            holograms.add(hologram);
            hologramNames.add(name);
        }

        if (plugin != null) {
            plugin.debug().debug(() -> "Created FancyHolograms hologram stack (3 lines) prefix=" + prefix);
        }
    }

    public void setHeaderLines(@Nullable Component nowPlaying, @Nullable Component eventLine) {
        if (nowPlaying != null) {
            setLine(HEADER_NOW_PLAYING, nowPlaying);
        }
        if (eventLine != null) {
            setLine(HEADER_EVENT, eventLine);
        }
    }

    public void setLyricsLines(Component top, Component middle, Component bottom) {
        // We created bottom->top for lyrics as i=0..2
        setLine(LYRICS_TOP, top);
        setLine(LYRICS_MIDDLE, middle);
        setLine(LYRICS_BOTTOM, bottom);
    }

    private void setLine(int index, Component component) {
        try {
            Hologram hologram = holograms.get(index);
            if (!(hologram.getData() instanceof TextHologramData data)) {
                return;
            }

            String legacy = LEGACY.serialize(component);
            if (legacy.equals(lastLines[index])) {
                return;
            }
            lastLines[index] = legacy;

            data.setText(List.of(legacy));
            hologram.queueUpdate();
        } catch (Exception e) {
            if (plugin != null) {
                plugin.debug().warn("Failed to update hologram line index=" + index, e);
            }
        }
    }

    @Override
    public void close() {
        // FancyHolograms internals changed between versions; try a few removal paths.
        for (Hologram hologram : holograms) {
            if (hologram == null) {
                continue;
            }

            // Force-hide first to ensure client-side entities are removed.
            try {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    try {
                        hologram.forceHideHologram(p);
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }

            try {
                manager.removeHologram(hologram);
            } catch (Throwable t) {
                if (plugin != null) {
                    plugin.debug().debug(() -> "removeHologram(instance) failed: " + t.getClass().getSimpleName());
                }
            }

            try {
                hologram.deleteHologram();
            } catch (Throwable t) {
                if (plugin != null) {
                    plugin.debug().debug(() -> "deleteHologram() failed: " + t.getClass().getSimpleName());
                }
            }
        }

        for (String name : hologramNames) {
            if (name == null || name.isBlank()) {
                continue;
            }
            tryRemoveByName(name);
        }
        holograms.clear();
        hologramNames.clear();
    }

    private void tryRemoveByName(String name) {
        try {
            manager.getHologram(name).ifPresent(holo -> {
                try {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        try {
                            holo.forceHideHologram(p);
                        } catch (Throwable ignored) {
                        }
                    }
                } catch (Throwable ignored) {
                }
                try {
                    manager.removeHologram(holo);
                } catch (Throwable ignored) {
                }
                try {
                    holo.deleteHologram();
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }
}
