package me.Luki.karaoke.hologram;

import me.Luki.karaoke.Karaoke;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public record KaraokePlacement(Location baseLocation, float yaw, double lineSpacing, double headerSpacing) {

    public static KaraokePlacement fromPlayer(Karaoke plugin, Player player) {
        return fromLocation(plugin, player.getLocation());
    }

    public static KaraokePlacement fromLocation(Karaoke plugin, Location loc) {
        if (loc == null || loc.getWorld() == null) {
            throw new IllegalArgumentException("Nie można ustawić hologramu: brak świata.");
        }

        float cardinalYaw = roundToCardinal(loc.getYaw());

        double blockInset = clamp(plugin.getConfig().getDouble("hologram.blockInset", 0.5D), 0.0D, 0.5D);
        double x = Math.floor(loc.getX()) + 0.5D;
        double z = Math.floor(loc.getZ()) + 0.5D;
        int yaw = normalizeYaw(cardinalYaw);
        switch (yaw) {
            case 0 -> z = Math.floor(loc.getZ()) + (1.0D - blockInset);      // South (+Z)
            case 180 -> z = Math.floor(loc.getZ()) + blockInset;             // North (-Z)
            case 90 -> x = Math.floor(loc.getX()) + blockInset;              // West (-X)
            case 270 -> x = Math.floor(loc.getX()) + (1.0D - blockInset);    // East (+X)
            default -> {
                // Keep centered if yaw isn't perfectly cardinal.
            }
        }

        double baseYOffset = plugin.getConfig().getDouble("hologram.baseYOffset", 2.0D);
        double y = Math.floor(loc.getY()) + baseYOffset;

        float yawOffset = (float) plugin.getConfig().getDouble("hologram.yawOffset", 180.0D);
        float finalYaw = cardinalYaw + yawOffset;

        double lineSpacing = plugin.getConfig().getDouble("hologram.lineSpacing", 1.0D);
        double headerSpacing = plugin.getConfig().getDouble("hologram.lineSpacingAfterHeader", 1.0D);

        Location base = new Location(loc.getWorld(), x, y, z, finalYaw, 0F);
        return new KaraokePlacement(base, finalYaw, lineSpacing, headerSpacing);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int normalizeYaw(float yaw) {
        int y = Math.round(yaw) % 360;
        if (y < 0) {
            y += 360;
        }
        return y;
    }

    private static float roundToCardinal(float yaw) {
        float y = yaw % 360F;
        if (y < 0F) {
            y += 360F;
        }
        return Math.round(y / 90F) * 90F;
    }
}
