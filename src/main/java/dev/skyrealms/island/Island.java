/*
 * SkyLands (SkyRealms) - floating-island survival for Paper
 * Copyright (C) 2026 Hammond Digital Studios
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License version 3, together with the additional terms in the NOTICE
 * file (section 7: you must keep the attribution "SkyLands by Hammond Digital Studios" and mark
 * modified versions). This program is distributed WITHOUT ANY WARRANTY. See the LICENSE and
 * NOTICE files, or <https://www.gnu.org/licenses/>.
 */
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
    /** Island XP (level is derived from it), lifetime unique visits, and whether old XP was back-filled. */
    public long xp;
    public long visitorsTotal;
    public boolean xpBackfilled;
    /** Underside style id used for land grown from now on. */
    public String underside = "rock";
    /** Cosmetics. name is null for the default "<owner>'s Island". islets: x, y, z, radius of placed decorations. */
    public String name;
    public String theme, particles = "none", border = "none", decorations = "none";
    public final List<int[]> islets = new ArrayList<>();
    public final LinkedHashSet<String> biomes = new LinkedHashSet<>();
    public final List<PiecePlacement> pieces = new ArrayList<>();
    /** Home offset from the centre; null means "top of the centre column". */
    public double[] home;          // dx, y, dz, yaw, pitch
    /** This island's sea level. Fixed when the island is made, so changing islands.base-y only affects new islands. */
    public int seaY = 180;

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

    /** The home the owner set, or else the nearest safe solid ground to the island's centre. */
    public Location home(World world) {
        if (home != null) {
            return new Location(world, centerX + home[0], home[1], centerZ + home[2], (float) home[3], (float) home[4]);
        }
        Location safe = safeSpot(world);
        if (safe != null) return safe;
        int y = world.getHighestBlockYAt(centerX, centerZ, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (y < seaY - 64) y = seaY; // nothing pasted yet
        return new Location(world, centerX + 0.5, y + 1, centerZ + 0.5);
    }

    private static final java.util.Set<org.bukkit.Material> UNSAFE_FLOOR = java.util.EnumSet.of(
            org.bukkit.Material.MAGMA_BLOCK, org.bukkit.Material.CACTUS, org.bukkit.Material.CAMPFIRE,
            org.bukkit.Material.SOUL_CAMPFIRE, org.bukkit.Material.POWDER_SNOW, org.bukkit.Material.POINTED_DRIPSTONE,
            org.bukkit.Material.SWEET_BERRY_BUSH);

    /** Searches outward from the centre for dry solid ground with two free blocks above it. */
    public Location safeSpot(World world) {
        int max = half() + 8;
        for (int r = 0; r <= max; r += 2) {
            for (int dx = -r; dx <= r; dx += 2) {
                for (int dz = -r; dz <= r; dz += 2) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;   // only the ring at this distance
                    Location l = standable(world, centerX + dx, centerZ + dz);
                    if (l != null) return l;
                }
            }
        }
        return null;
    }

    private Location standable(World w, int x, int z) {
        org.bukkit.block.Block top = w.getHighestBlockAt(x, z, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (top.getY() < seaY - 64) return null;
        org.bukkit.Material m = top.getType();
        if (!m.isSolid() || top.isLiquid() || UNSAFE_FLOOR.contains(m) || org.bukkit.Tag.LEAVES.isTagged(m)) return null;
        if (top.getBlockData() instanceof org.bukkit.block.data.Waterlogged wl && wl.isWaterlogged()) return null;
        org.bukkit.block.Block feet = top.getRelative(org.bukkit.block.BlockFace.UP), head = feet.getRelative(org.bukkit.block.BlockFace.UP);
        if (!feet.isPassable() || feet.isLiquid() || !head.isPassable() || head.isLiquid()) return null;
        if (feet.getType() == org.bukkit.Material.FIRE || feet.getType() == org.bukkit.Material.SWEET_BERRY_BUSH) return null;
        return new Location(w, x + 0.5, top.getY() + 1, z + 0.5);
    }
}
