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
package dev.skyrealms.decay;

import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Turns decayed land into dead land, one random column at a time. Each call to
 * {@link #attempt} touches at most one block, so the caller controls the cost with a budget.
 */
final class DecayConverter {
    private static final Set<Material> GRASSY = EnumSet.of(
            Material.GRASS_BLOCK, Material.PODZOL, Material.MYCELIUM, Material.DIRT_PATH,
            Material.MOSS_BLOCK, Material.ROOTED_DIRT);
    private static final Set<Material> DIRTY = EnumSet.of(Material.DIRT, Material.COARSE_DIRT);
    private static final Set<Material> STONY = EnumSet.of(
            Material.STONE, Material.COBBLESTONE, Material.MOSSY_COBBLESTONE, Material.SANDSTONE,
            Material.RED_SANDSTONE, Material.SAND, Material.RED_SAND, Material.GRAVEL, Material.CLAY,
            Material.TUFF, Material.CALCITE, Material.DEEPSLATE);
    private static final Set<Material> PLANTS = EnumSet.of(
            Material.SHORT_GRASS, Material.TALL_GRASS, Material.FERN, Material.LARGE_FERN,
            Material.DEAD_BUSH, Material.VINE, Material.SWEET_BERRY_BUSH, Material.SUGAR_CANE,
            Material.BAMBOO, Material.CACTUS, Material.LILY_PAD, Material.SEAGRASS, Material.TALL_SEAGRASS,
            Material.KELP, Material.KELP_PLANT, Material.BROWN_MUSHROOM, Material.RED_MUSHROOM,
            Material.PUMPKIN, Material.MELON, Material.SNOW);

    private final DecaySettings s;
    /** Player positions {x, y, z} for the current tick; blocks near them are left alone. */
    private final List<int[]> guarded = new ArrayList<>();

    DecayConverter(DecaySettings settings) { this.s = settings; }

    /** Call once per tick before any {@link #attempt}. */
    void beginTick(World w) {
        guarded.clear();
        for (Player p : w.getPlayers()) {
            var l = p.getLocation();
            guarded.add(new int[]{l.getBlockX(), l.getBlockY(), l.getBlockZ()});
        }
    }

    static int stageOf(DecaySettings s, double v) {
        return v >= s.stage3 ? 3 : v >= s.stage2 ? 2 : v >= s.stage1 ? 1 : 0;
    }

    /** Try to change one block in a random column of the chunk. */
    void attempt(World w, int chunkX, int chunkZ, double decay) {
        int stage = stageOf(s, decay);
        if (stage == 0) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int x = (chunkX << 4) + r.nextInt(16);
        int z = (chunkZ << 4) + r.nextInt(16);
        int y = w.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
        Block b = w.getBlockAt(x, y, z);
        Material m = b.getType();
        if (m.isAir() || nearPlayer(x, y, z)) return;

        if (isFoliage(m)) { b.setType(Material.AIR, false); return; }
        if (Tag.LOGS.isTagged(m)) { if (stage >= 2) b.setType(Material.AIR, false); return; }
        if (stage == 3) { crust(w, x, y, z); return; }
        if (b.isLiquid()) return;

        if (GRASSY.contains(m)) { b.setType(Material.COARSE_DIRT, false); return; }
        if (stage >= 2) {
            if (DIRTY.contains(m)) { if (m != Material.COARSE_DIRT) b.setType(Material.COARSE_DIRT, false); return; }
            if (STONY.contains(m) || Tag.BASE_STONE_OVERWORLD.isTagged(m)) b.setType(Material.BLACKSTONE, false);
        }
    }

    /** Stage 3: the topmost non-bedrock block of the column becomes bedrock, down to bedrock-depth layers. */
    private void crust(World w, int x, int y, int z) {
        int floor = w.getMinHeight() + 8;
        int layers = 0;
        while (y > floor && w.getBlockAt(x, y, z).getType() == Material.BEDROCK) {
            if (++layers >= s.bedrockDepth) return;
            y--;
        }
        if (y <= floor) return;
        Block target = w.getBlockAt(x, y, z);
        if (target.getType().isAir() || nearPlayer(x, y, z)) return; // a cave beneath the crust stays as it is
        target.setType(Material.BEDROCK, false);
    }

    private boolean isFoliage(Material m) {
        return Tag.LEAVES.isTagged(m) || Tag.FLOWERS.isTagged(m) || Tag.SAPLINGS.isTagged(m)
                || Tag.CROPS.isTagged(m) || PLANTS.contains(m);
    }

    private boolean nearPlayer(int x, int y, int z) {
        int r = s.safeRadius;
        for (int[] p : guarded) {
            if (Math.abs(p[0] - x) <= r && Math.abs(p[2] - z) <= r && Math.abs(p[1] - y) <= r + 2) return true;
        }
        return false;
    }
}
