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

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Turns decayed land into dead land, one random block at a time. Each call to {@link #attempt}
 * touches at most one block, so the caller controls the cost with a budget.
 *
 * <ul>
 *   <li>Below stage 1 (25): nothing.</li>
 *   <li>Stage 1, drying (25-50): the land dries out. Leaves and plants die, pools dry up,
 *       grass and dirt become coarse dirt, sand, gravel or stone. The chance of a change starts
 *       near zero at 25 and climbs to certain at 50, so it creeps in.</li>
 *   <li>Stage 2, wasteland (50-75): stone becomes blackstone and deepslate, logs harden to stone,
 *       water boils away or turns to lava, the ground becomes sand, clay, terracotta and stone,
 *       and the first patches of bedrock appear. Also reaches a few blocks under the surface.</li>
 *   <li>Stage 3, barren (75+): bedrock spreads across the surface and down, top-down.</li>
 * </ul>
 */
final class DecayConverter {
    private static final Set<Material> GRASSY = EnumSet.of(
            Material.GRASS_BLOCK, Material.PODZOL, Material.MYCELIUM, Material.DIRT_PATH,
            Material.MOSS_BLOCK, Material.ROOTED_DIRT);
    private static final Set<Material> DIRTY = EnumSet.of(Material.DIRT, Material.COARSE_DIRT, Material.MUD, Material.FARMLAND);
    private static final Set<Material> STONY = EnumSet.of(
            Material.STONE, Material.COBBLESTONE, Material.MOSSY_COBBLESTONE, Material.ANDESITE, Material.DIORITE,
            Material.GRANITE, Material.TUFF, Material.CALCITE, Material.SANDSTONE, Material.RED_SANDSTONE);
    private static final Set<Material> DRY_GROUND = EnumSet.of(
            Material.SAND, Material.RED_SAND, Material.GRAVEL, Material.CLAY, Material.TERRACOTTA);
    private static final Set<Material> FROZEN = EnumSet.of(
            Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE, Material.SNOW_BLOCK, Material.POWDER_SNOW);
    private static final Set<Material> PLANTS = EnumSet.of(
            Material.SHORT_GRASS, Material.TALL_GRASS, Material.FERN, Material.LARGE_FERN,
            Material.VINE, Material.SWEET_BERRY_BUSH, Material.SUGAR_CANE, Material.BAMBOO, Material.CACTUS,
            Material.LILY_PAD, Material.SEAGRASS, Material.TALL_SEAGRASS, Material.KELP, Material.KELP_PLANT,
            Material.BROWN_MUSHROOM, Material.RED_MUSHROOM, Material.PUMPKIN, Material.MELON, Material.SNOW,
            Material.MOSS_CARPET, Material.GLOW_LICHEN);

    // What the ground turns into. Numbers are relative weights.
    private static final Pick GRASS_DRY = Pick.of(Material.COARSE_DIRT, 50, Material.DIRT, 15, Material.SAND, 20, Material.GRAVEL, 5, Material.STONE, 10);
    private static final Pick DIRT_DRY = Pick.of(Material.COARSE_DIRT, 30, Material.SAND, 40, Material.GRAVEL, 15, Material.STONE, 15);
    private static final Pick WATER_BED = Pick.of(Material.SAND, 55, Material.CLAY, 30, Material.GRAVEL, 15);
    /** What boiled-off water leaves behind in the wasteland. */
    private static final Pick BOILED = Pick.of(Material.MAGMA_BLOCK, 75, Material.LAVA, 25);
    private static final Pick GROUND_WASTE = Pick.of(Material.SAND, 35, Material.RED_SAND, 10, Material.CLAY, 10,
            Material.TERRACOTTA, 10, Material.STONE, 15, Material.BLACKSTONE, 10, Material.COARSE_DIRT, 10);
    private static final Pick STONE_WASTE = Pick.of(Material.BLACKSTONE, 50, Material.DEEPSLATE, 25,
            Material.COBBLED_DEEPSLATE, 15, Material.BASALT, 10);
    private static final Pick DRY_WASTE = Pick.of(Material.STONE, 40, Material.BLACKSTONE, 40, Material.SANDSTONE, 20);

    private final DecaySettings s;
    /** Player positions {x, y, z} for the current tick; blocks near them are left alone. */
    private final List<int[]> guarded = new ArrayList<>();

    /** Called whenever decay turns a block into bedrock (e.g. to leave a Void Shard). */
    volatile java.util.function.Consumer<Block> onBedrock;

    DecayConverter(DecaySettings settings) { this.s = settings; }

    private void bedrocked(Block b) {
        var hook = onBedrock;
        if (hook != null) hook.accept(b);
    }

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

    /** 0 at {@code from}, 1 at {@code to} and above. */
    private static double progress(double v, double from, double to) {
        return to <= from ? 1 : Math.max(0, Math.min(1, (v - from) / (to - from)));
    }

    /** Try to change one block in a random column of the chunk. */
    void attempt(World w, int chunkX, int chunkZ, double decay) {
        if (decay < s.stage1) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int x = (chunkX << 4) + r.nextInt(16);
        int z = (chunkZ << 4) + r.nextInt(16);
        int top = w.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
        int floor = w.getMinHeight() + 8;
        if (top <= floor) return;

        // Stage 3: bedrock takes the column most of the time; otherwise the wasteland keeps spreading.
        if (decay >= s.stage3 && r.nextInt(10) < 6) { crust(w, x, top, z); return; }

        if (decay < s.stage2) {
            // Gradual: almost nothing just past 25, a change on every attempt by 50.
            if (r.nextDouble() >= progress(decay, s.stage1, s.stage2)) return;
            Block b = w.getBlockAt(x, top, z);
            if (!nearPlayer(x, top, z)) dry(w, b, r);
            return;
        }

        // Stage 2+: sometimes reach under the surface so the wasteland goes deeper than one layer.
        int y = r.nextInt(10) < 3 ? top - 1 - r.nextInt(s.subsurfaceDepth) : top;
        if (y <= floor || nearPlayer(x, y, z)) return;
        Block b = w.getBlockAt(x, y, z);
        double t = progress(decay, s.stage2, s.stage3);
        if (r.nextDouble() < 0.5 + 0.5 * t) waste(w, b, r, t);
        else dry(w, b, r);
    }

    /** Stage 1 changes: the land dries out. */
    private void dry(World w, Block b, ThreadLocalRandom r) {
        Material m = b.getType();
        if (m.isAir() || m == Material.BEDROCK) return;
        if (Tag.LEAVES.isTagged(m)) { place(b, Material.AIR); return; }
        if (isPlant(m)) {
            boolean shrub = (m == Material.SHORT_GRASS || m == Material.FERN) && r.nextInt(10) < 3;
            place(b, shrub ? Material.DEAD_BUSH : Material.AIR);
            return;
        }
        if (m == Material.WATER) { dryWater(b, r); return; }
        if (FROZEN.contains(m)) { place(b, m == Material.SNOW_BLOCK || m == Material.POWDER_SNOW ? Material.SAND : Material.WATER); return; }
        if (GRASSY.contains(m)) { set(b, GRASS_DRY.roll(r)); return; }
        if (DIRTY.contains(m)) { set(b, DIRT_DRY.roll(r)); }
    }

    /** Stage 2 changes: wasteland. {@code t} is 0 at 50 and 1 at 75. */
    private void waste(World w, Block b, ThreadLocalRandom r, double t) {
        Material m = b.getType();
        if (m.isAir() || m == Material.BEDROCK) return;
        if (m == Material.WATER) {
            // The water boils: some steams off, some becomes lava.
            Location l = b.getLocation().add(0.5, 1, 0.5);
            w.spawnParticle(Particle.CLOUD, l, 3, 0.3, 0.2, 0.3, 0.01);
            if (r.nextInt(8) == 0) w.playSound(l, Sound.BLOCK_FIRE_EXTINGUISH, 0.5f, 0.8f);
            boil(b, r, true);
            return;
        }
        if (Tag.LOGS.isTagged(m)) { place(b, Material.STONE); return; }
        if (Tag.LEAVES.isTagged(m) || isPlant(m) || FROZEN.contains(m)) { dry(w, b, r); return; }
        if (!b.getType().isSolid()) return;
        // The first bedrock appears, more often as the chunk nears 75.
        if (r.nextDouble() < 0.04 * t) { place(b, Material.BEDROCK); bedrocked(b); return; }
        if (GRASSY.contains(m) || DIRTY.contains(m)) { set(b, GROUND_WASTE.roll(r)); return; }
        if (STONY.contains(m) || Tag.BASE_STONE_OVERWORLD.isTagged(m)) {
            if (m != Material.DEEPSLATE && m != Material.BLACKSTONE) set(b, STONE_WASTE.roll(r));
            return;
        }
        if (DRY_GROUND.contains(m) && r.nextDouble() < 0.3 * t) set(b, DRY_WASTE.roll(r));
    }

    /** A pool dries up; what it sat on becomes a dry bed. */
    /**
     * Water boils down instead of vanishing: each hit makes it visibly shallower, with steam.
     * When it's a puddle the water block itself becomes ground, so pools never leave holes:
     * sand, clay and gravel while drying; magma and lava in the wasteland.
     */
    private void dryWater(Block b, ThreadLocalRandom r) { boil(b, r, false); }

    private void boil(Block b, ThreadLocalRandom r, boolean wasteland) {
        var w = b.getWorld();
        var at = b.getLocation().add(0.5, 1, 0.5);
        w.spawnParticle(Particle.CLOUD, at, 3, 0.3, 0.2, 0.3, 0.01);
        if (r.nextInt(10) == 0) w.playSound(at, Sound.BLOCK_FIRE_EXTINGUISH, 0.4f, 0.8f);
        if (b.getBlockData() instanceof org.bukkit.block.data.Levelled lv && lv.getLevel() < 6) {
            lv.setLevel(Math.min(7, lv.getLevel() + 2 + r.nextInt(2)));   // shallower, never ticked
            b.setBlockData(lv, false);
            return;
        }
        Block below = b.getRelative(0, -1, 0);
        if (below.getType() != Material.WATER) {
            // Shallow: the last of the water becomes ground right here.
            place(b, wasteland ? BOILED.roll(r) : WATER_BED.roll(r));
            return;
        }
        // Deep water: the sediment settles at the bottom of the column and the surface sinks by one
        // block, so oceans silt up from the floor instead of growing floating blocks.
        Block bottom = below;
        for (int i = 0; i < 96 && bottom.getRelative(0, -1, 0).getType() == Material.WATER; i++) bottom = bottom.getRelative(0, -1, 0);
        Material sediment;
        do { sediment = wasteland ? BOILED.roll(r) : WATER_BED.roll(r); } while (sediment == Material.LAVA);
        bottom.setType(sediment, false);
        // In the wasteland the boiling surface often catches: lava takes its place and flows into
        // the gaps left by boiling around it, so lava pools spread across lakes and oceans.
        place(b, wasteland && r.nextInt(100) < 40 ? Material.LAVA : Material.AIR);
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
        Material m = target.getType();
        if (m.isAir() || nearPlayer(x, y, z)) return; // a cave beneath the crust stays as it is
        if (m == Material.LAVA) return;                                   // lava is never crusted over
        if (m == Material.WATER) { place(target, Material.LAVA); return; } // barren water becomes lava
        if (!m.isSolid() && !target.isLiquid()) { place(target, Material.AIR); return; } // plants and debris just die
        place(target, Material.BEDROCK);
        bedrocked(target);
    }

    private static void set(Block b, Material to) {
        if (b.getType() != to) place(b, to);
    }

    private static final org.bukkit.block.BlockFace[] SIDES = {org.bukkit.block.BlockFace.UP, org.bukkit.block.BlockFace.DOWN,
            org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST};

    /**
     * Changes a block. Normally without physics (cheap, nothing pops off), but whenever liquid is
     * involved (placing water or lava, replacing a liquid, or touching one) physics runs, so lava
     * spreads, water flows into gaps, and pools drain naturally.
     */
    private static void place(Block b, Material to) {
        // Physics only around lava, so lava spreads. Water is never ticked, so it can't refill gaps
        // or drain on its own; the corruption boils it down instead (see boil()).
        boolean lava = to == Material.LAVA || b.getType() == Material.LAVA;
        if (!lava) for (var f : SIDES) if (b.getRelative(f).getType() == Material.LAVA) { lava = true; break; }
        b.setType(to, lava);
    }

    private static boolean isPlant(Material m) {
        return Tag.FLOWERS.isTagged(m) || Tag.SAPLINGS.isTagged(m) || Tag.CROPS.isTagged(m) || PLANTS.contains(m);
    }

    private boolean nearPlayer(int x, int y, int z) {
        int r = s.safeRadius;
        for (int[] p : guarded) {
            if (Math.abs(p[0] - x) <= r && Math.abs(p[2] - z) <= r && Math.abs(p[1] - y) <= r + 2) return true;
        }
        return false;
    }

    /** A weighted random choice of materials. */
    private record Pick(Material[] mats, int[] cumulative, int total) {
        static Pick of(Object... pairs) {
            int n = pairs.length / 2;
            Material[] mats = new Material[n];
            int[] cum = new int[n];
            int sum = 0;
            for (int i = 0; i < n; i++) {
                mats[i] = (Material) pairs[i * 2];
                sum += (Integer) pairs[i * 2 + 1];
                cum[i] = sum;
            }
            return new Pick(mats, cum, sum);
        }

        Material roll(ThreadLocalRandom r) {
            int v = r.nextInt(total);
            for (int i = 0; i < cumulative.length; i++) if (v < cumulative[i]) return mats[i];
            return mats[mats.length - 1];
        }
    }
}
