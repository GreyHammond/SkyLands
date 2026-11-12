package dev.skyrealms.island;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.*;

public final class Island {
    public final UUID owner;
    public final int index;        // position on the sky-world spiral grid
    public final int centerX, centerZ;
    public Tier tier;
    public final Set<UUID> members = new HashSet<>();
    public final LinkedHashSet<String> biomes = new LinkedHashSet<>();
    public final List<PiecePlacement> pieces = new ArrayList<>();
    /** Home offset from the centre; null means "top of the centre column". */
    public double[] home;          // dx, y, dz, yaw, pitch

    public Island(UUID owner, int index, int centerX, int centerZ, Tier tier) {
        this.owner = owner;
        this.index = index;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.tier = tier;
    }

    public boolean canBuild(UUID player) { return owner.equals(player) || members.contains(player); }

    public int half() { return tier.footprint / 2; }

    /** True if the X/Z is inside this island's buildable square. */
    public boolean contains(double x, double z) {
        return Math.abs(x - centerX) <= half() && Math.abs(z - centerZ) <= half();
    }

    /** Like {@link #contains} but with a margin, since round pieces can bulge a little past the square border. */
    public boolean containsLand(double x, double z) {
        int h = half() + 24;
        return Math.abs(x - centerX) <= h && Math.abs(z - centerZ) <= h;
    }

    public Location home(World world, int baseY) {
        if (home != null) {
            return new Location(world, centerX + home[0], home[1], centerZ + home[2], (float) home[3], (float) home[4]);
        }
        int y = world.getHighestBlockYAt(centerX, centerZ);
        if (y < baseY - 64) y = baseY; // nothing pasted yet
        return new Location(world, centerX + 0.5, y + 1, centerZ + 0.5);
    }
}
