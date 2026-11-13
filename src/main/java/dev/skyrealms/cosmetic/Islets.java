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
package dev.skyrealms.cosmetic;

import dev.skyrealms.island.Island;
import dev.skyrealms.terrain.TerrainBiome;
import org.bukkit.Material;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Random;

/**
 * Floating decoration islets around an island. They float above sea level just outside the
 * buildable border, use the island's own surface, and are remembered so they can be removed
 * cleanly when the owner picks a different decoration set.
 */
public final class Islets {
    private Islets() {}

    /** Remove the island's current islets, then build the ones for {@code set}. */
    public static void apply(World w, Island island, Cosmetics.DecorationSet set, int seaY) {
        clear(w, island);
        island.decorations = set.id;
        if (set == Cosmetics.DecorationSet.NONE) return;
        Random rng = new Random(island.index * 7919L + set.ordinal());
        TerrainBiome biome = island.biomes.stream().map(TerrainBiome::byId)
                .filter(b -> b != null && !b.isWater()).findFirst().orElse(TerrainBiome.PLAINS);
        int half = island.half();

        for (int n = 0; n < set.islets; n++) {
            double angle = (Math.PI * 2 * n / set.islets) + rng.nextDouble() * 0.6;
            double dist = half + 8 + rng.nextDouble() * 10;
            int r = 3 + rng.nextInt(set == Cosmetics.DecorationSet.FEW ? 3 : 4);
            int[] xz = onSquare(island, angle, dist);
            int x = xz[0], z = xz[1];
            int y = seaY + 14 + rng.nextInt(22);
            if (!clearAround(w, x, y, z, r + 2)) continue;
            buildIslet(w, x, y, z, r, biome, rng, set != Cosmetics.DecorationSet.FEW && r >= 5 && rng.nextBoolean());
            island.islets.add(new int[]{x, y, z, r});
        }
        if (set == Cosmetics.DecorationSet.RING) {
            for (int n = 0; n < 16; n++) {
                double angle = Math.PI * 2 * n / 16 + rng.nextDouble() * 0.2;
                double dist = half + 19 + rng.nextDouble() * 2;
                int[] xz = onSquare(island, angle, dist);
                int x = xz[0], z = xz[1];
                int y = seaY + 26 + (int) Math.round(Math.sin(n * 0.9) * 4);
                int r = 1 + rng.nextInt(2);
                if (!clearAround(w, x, y, z, r + 1)) continue;
                buildRock(w, x, y, z, r, rng);
                island.islets.add(new int[]{x, y, z, r});
            }
        }
    }

    /** A point in direction {@code angle} that is {@code dist} blocks out from the centre on the square border's terms. */
    private static int[] onSquare(Island island, double angle, double dist) {
        double c = Math.cos(angle), s = Math.sin(angle);
        double k = dist / Math.max(Math.abs(c), Math.abs(s));
        return new int[]{island.centerX + (int) Math.round(c * k), island.centerZ + (int) Math.round(s * k)};
    }

    /** Remove all islet blocks (only blocks inside each islet's own shape area). */
    public static void clear(World w, Island island) {
        for (int[] it : island.islets) {
            int r = it[3];
            for (int dx = -r - 1; dx <= r + 1; dx++)
                for (int dz = -r - 1; dz <= r + 1; dz++)
                    for (int dy = -(int) (r * 1.6) - 2; dy <= r + 9; dy++) {
                        Block b = w.getBlockAt(it[0] + dx, it[1] + dy, it[2] + dz);
                        if (!b.getType().isAir()) b.setType(Material.AIR, false);
                    }
        }
        island.islets.clear();
    }

    private static boolean clearAround(World w, int x, int y, int z, int r) {
        for (int dx = -r; dx <= r; dx += 2) for (int dz = -r; dz <= r; dz += 2) for (int dy = -r * 2; dy <= r + 6; dy += 2)
            if (!w.getBlockAt(x + dx, y + dy, z + dz).getType().isAir()) return false;
        return true;
    }

    private static void buildIslet(World w, int cx, int cy, int cz, int r, TerrainBiome biome, Random rng, boolean tree) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double d = Math.hypot(dx, dz) / (r + 0.3);
                if (d > 1) continue;
                int top = cy + (int) Math.round(1.5 * (1 - d * d));
                int bottom = cy - (int) Math.round(r * 1.5 * Math.pow(1 - d, 1.4)) - 1;
                for (int y = bottom; y <= top; y++) {
                    int depth = top - y;
                    Material m = depth == 0 ? biome.surface(rng.nextDouble())
                            : depth <= 2 ? biome.fill(depth, y)
                            : rng.nextDouble() < 0.15 ? Material.ANDESITE : Material.STONE;
                    w.getBlockAt(cx + dx, y, cz + dz).setType(m, false);
                }
            }
        }
        int topY = cy + 1;
        if (tree) {
            TreeType tt = biome.tree(0);
            w.generateTree(new org.bukkit.Location(w, cx, topY + 1, cz), rng, tt != null ? tt : TreeType.TREE);
        }
    }

    private static void buildRock(World w, int cx, int cy, int cz, int r, Random rng) {
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) for (int dy = -r; dy <= r; dy++) {
            if (dx * dx + dy * dy + dz * dz > r * r + 1) continue;
            double v = rng.nextDouble();
            w.getBlockAt(cx + dx, cy + dy, cz + dz).setType(v < 0.5 ? Material.STONE : v < 0.8 ? Material.COBBLESTONE : Material.MOSSY_COBBLESTONE, false);
        }
    }
}
