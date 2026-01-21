package me.Luki.karaoke.hologram;

import me.Luki.karaoke.Karaoke;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public record KaraokePlacement(Location baseLocation, float yaw, double lineSpacing) {

    public static KaraokePlacement fromPlayer(Karaoke plugin, Player player) {
        return fromLocation(plugin, player.getLocation());
    }

    public static KaraokePlacement fromLocation(Karaoke plugin, Location loc) {
        if (loc == null || loc.getWorld() == null) {
            throw new IllegalArgumentException("Nie można ustawić hologramu: brak świata.");
        }

        double x = Math.floor(loc.getX()) + 0.5D;
        double z = Math.floor(loc.getZ()) + 0.5D;

        double baseYOffset = plugin.getConfig().getDouble("hologram.baseYOffset", 2.0D);
        double y = Math.floor(loc.getY()) + baseYOffset;

        float cardinalYaw = roundToCardinal(loc.getYaw());
        float yawOffset = (float) plugin.getConfig().getDouble("hologram.yawOffset", 180.0D);
        float finalYaw = cardinalYaw + yawOffset;

        double lineSpacing = plugin.getConfig().getDouble("hologram.lineSpacing", 1.0D);

        Location base = new Location(loc.getWorld(), x, y, z, finalYaw, 0F);
        return new KaraokePlacement(base, finalYaw, lineSpacing);
    }

    private static float roundToCardinal(float yaw) {
        float y = yaw % 360F;
        if (y < 0F) {
            y += 360F;
        }
        return Math.round(y / 90F) * 90F;
    }
}
