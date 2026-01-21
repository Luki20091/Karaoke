package me.Luki.karaoke.hologram;

import de.oliver.fancyholograms.api.HologramManager;
import de.oliver.fancyholograms.api.FancyHologramsPlugin;
import de.oliver.fancyholograms.api.data.TextHologramData;
import de.oliver.fancyholograms.api.hologram.Hologram;
import me.Luki.karaoke.Karaoke;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class KaraokeHologram implements AutoCloseable {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final Karaoke plugin;
    private final HologramManager manager;
    private final List<Hologram> holograms;
    private final String[] lastLines;

    public KaraokeHologram(Karaoke plugin, KaraokePlacement placement) {
        this.plugin = plugin;
        this.manager = FancyHologramsPlugin.get().getHologramManager();
        this.holograms = new ArrayList<>(3);
        this.lastLines = new String[] { null, null, null };

        Location base = placement.baseLocation().clone();
        double lineSpacing = placement.lineSpacing();

        // Unique prefix so multiple players can run karaoke at the same time
        String prefix = "karaoke_" + UUID.randomUUID() + "_";

        // 3 lines, 3 blocks tall (bottom/middle/top)
        for (int i = 0; i < 3; i++) {
            Location lineLoc = base.clone().add(0D, i * lineSpacing, 0D);
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
        }

        if (plugin != null) {
            plugin.debug().debug(() -> "Created FancyHolograms hologram stack (3 lines) prefix=" + prefix);
        }
    }

    public void setLines(Component top, Component middle, Component bottom) {
        // We created bottom->top as i=0..2
        setLine(2, top);
        setLine(1, middle);
        setLine(0, bottom);
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
        for (Hologram hologram : holograms) {
            try {
                manager.removeHologram(hologram);
            } catch (Exception ignored) {
            }

            try {
                hologram.deleteHologram();
            } catch (Exception ignored) {
            }
        }
        holograms.clear();
    }
}
