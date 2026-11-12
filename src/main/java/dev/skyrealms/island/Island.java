package dev.skyrealms.island;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.*;

public final class Island {
    /** Changes on transfer; always go through IslandManager.transfer so lookups stay in sync. */
    public UUID owner;
    public final int index;        // position on the sky-world spiral grid
    public final int centerX, centerZ;
    public Tier tier;
    /** Co-owners can manage the island (but not delete or transfer it). A player co-owns at most one island. */
    public final Set<UUID> coOwners = new HashSet<>();
    /** Friends get the FRIEND permission set. A player can be a friend on many islands. */
    public final Set<UUID> friends = new HashSet<>();
    /** Admin-set: never auto-pruned. */
    public boolean pruneExempt;
    public final LinkedHashSet<String> biomes = new LinkedHashSet<>();
    public final List<PiecePlacement> pieces = new ArrayList<>();
    /** Home offset from the centre; null means "top of the centre column". */
    public double[] home;          // dx, y, dz, yaw, pitch

    private final EnumSet<Flag> visitorFlags = EnumSet.noneOf(Flag.class);
    private final EnumSet<Flag> friendFlags = EnumSet.noneOf(Flag.class);
    private final EnumSet<Flag> islandFlags = EnumSet.noneOf(Flag.class);

    public Island(UUID owner, int index, int centerX, int centerZ, Tier tier) {
        this.owner = owner;
        this.index = index;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.tier = tier;
        for (Flag f : Flag.values()) {
            if (f.scope == Flag.Scope.ISLAND) { if (f.defaultVisitor) islandFlags.add(f); }
            else {
                if (f.defaultVisitor) visitorFlags.add(f);
                if (f.defaultFriend) friendFlags.add(f);
            }
        }
    }

    public Role roleOf(UUID player) {
        if (owner.equals(player)) return Role.OWNER;
        if (coOwners.contains(player)) return Role.COOWNER;
        return friends.contains(player) ? Role.FRIEND : Role.VISITOR;
    }

    /** Owner plus co-owners. */
    public Set<UUID> managers() {
        Set<UUID> out = new HashSet<>(coOwners);
        out.add(owner);
        return out;
    }

    /** Can this player do a ROLE-scoped action here? */
    public boolean allows(UUID player, Flag flag) {
        return switch (roleOf(player)) {
            case OWNER, COOWNER -> true;
            case FRIEND -> friendFlags.contains(flag);
            case VISITOR -> visitorFlags.contains(flag);
        };
    }

    /** Is an ISLAND-scoped setting on? */
    public boolean setting(Flag flag) { return islandFlags.contains(flag); }

    /** The flag set behind a role (or the island settings when role is null). */
    public EnumSet<Flag> flags(Role role) {
        if (role == null) return islandFlags;
        return role == Role.FRIEND ? friendFlags : visitorFlags;
    }

    public boolean get(Role role, Flag flag) { return flags(role).contains(flag); }

    public void set(Role role, Flag flag, boolean on) {
        if (on) flags(role).add(flag); else flags(role).remove(flag);
    }

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
        int y = world.getHighestBlockYAt(centerX, centerZ, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (y < baseY - 64) y = baseY; // nothing pasted yet
        return new Location(world, centerX + 0.5, y + 1, centerZ + 0.5);
    }
}
