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
package dev.skyrealms.terrain;

import dev.skyrealms.island.Island;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Converts the rock under an island's existing land to another underside style.
 *
 * Only natural underside blocks well below the surface are swapped (stone, ores, ice, moss,
 * dripstone, amethyst, blackstone, ...), so the surface, the dirt/sand layers and anything
 * players built on top are never touched. Any other block (planks, chests, glass in a mine)
 * stays, and dug-out tunnels stay empty. Old hanging decorations are replaced with the new style's.
 */
public final class UndersideRestyler extends BukkitRunnable {
    private static final Set<Material> BODY = EnumSet.of(
            Material.STONE, Material.ANDESITE, Material.GRANITE, Material.DIORITE, Material.TUFF, Material.COBBLESTONE,
            Material.COAL_ORE, Material.IRON_ORE, Material.COPPER_ORE, Material.GOLD_ORE, Material.REDSTONE_ORE,
            Material.LAPIS_ORE, Material.DIAMOND_ORE, Material.EMERALD_ORE,
            Material.ROOTED_DIRT, Material.COARSE_DIRT, Material.DIRT, Material.MUD,
            Material.PACKED_ICE, Material.BLUE_ICE, Material.SNOW_BLOCK, Material.MOSS_BLOCK, Material.CLAY,
            Material.DRIPSTONE_BLOCK, Material.CALCITE, Material.AMETHYST_BLOCK, Material.BUDDING_AMETHYST,
            Material.SMOOTH_BASALT, Material.BASALT, Material.BLACKSTONE, Material.NETHERRACK, Material.MAGMA_BLOCK,
            Material.NETHER_QUARTZ_ORE, Material.STONE_BRICKS, Material.MOSSY_STONE_BRICKS, Material.CRACKED_STONE_BRICKS,
            Material.CHISELED_STONE_BRICKS, Material.SANDSTONE);
    private static final Set<Material> HANGING = EnumSet.of(
            Material.HANGING_ROOTS, Material.CAVE_VINES, Material.CAVE_VINES_PLANT, Material.SPORE_BLOSSOM,
            Material.POINTED_DRIPSTONE, Material.AMETHYST_CLUSTER, Material.WEEPING_VINES, Material.WEEPING_VINES_PLANT,
            Material.SHROOMLIGHT, Material.IRON_CHAIN, Material.LANTERN, Material.PACKED_ICE);
    private static final Set<Material> WATER_STUFF = EnumSet.of(Material.WATER, Material.SEAGRASS, Material.TALL_SEAGRASS,
            Material.KELP, Material.KELP_PLANT);

    private final int seaY, columnsPerTick;
    private final Queue<Job> jobs = new ArrayDeque<>();
    private final Map<Material, BlockData> data = new EnumMap<>(Material.class);
    private final Map<String, BlockData> strings = new HashMap<>();

    private static final class Job {
        final World world; final Island island; final UndersideStyle style; final Runnable done; final Noise noise;
        final List<int[]> columns = new ArrayList<>(); int cursor; final Set<Long> chunks = new HashSet<>();
        Job(World w, Island i, UndersideStyle s, Runnable done) { world = w; island = i; style = s; this.done = done; noise = new Noise(i.index * 31L + s.ordinal()); }
    }

    public UndersideRestyler(Plugin plugin, int seaY, int columnsPerTick) {
        this.seaY = seaY;
        this.columnsPerTick = Math.max(50, columnsPerTick);
        runTaskTimer(plugin, 5L, 1L);
    }

    /** Queue a restyle of the island's whole underside. done runs on the main thread when finished. */
    public void restyle(World world, Island island, UndersideStyle style, Runnable done) {
        Job j = new Job(world, island, style, done);
        int h = island.half() + 24;
        for (int x = island.centerX - h; x <= island.centerX + h; x++)
            for (int z = island.centerZ - h; z <= island.centerZ + h; z++) j.columns.add(new int[]{x, z});
        jobs.add(j);
    }

    public boolean busy(Island i) { return jobs.stream().anyMatch(j -> j.island == i); }

    @Override
    public void run() {
        Job j = jobs.peek();
        if (j == null) return;
        for (int n = 0; n < columnsPerTick && j.cursor < j.columns.size(); n++) {
            int[] c = j.columns.get(j.cursor++);
            column(j, c[0], c[1]);
        }
        if (j.cursor < j.columns.size()) return;
        jobs.poll();
        for (long key : j.chunks) j.world.refreshChunk((int) (key >> 32), (int) key);
        if (j.done != null) j.done.run();
    }

    private void column(Job j, int x, int z) {
        World w = j.world;
        Material atSea = w.getBlockAt(x, j.island.seaY, z).getType();
        if (atSea.isAir()) return;                       // not part of the island (or an islet above it)
        int limit;                                       // only change blocks at or below this height
        if (WATER_STUFF.contains(atSea)) {
            int y = j.island.seaY;
            while (y > w.getMinHeight() + 1 && WATER_STUFF.contains(w.getBlockAt(x, y, z).getType())) y--;
            limit = y - 3;                               // keep the sea floor and its sand
        } else {
            limit = j.island.seaY - 4;                            // well below any land surface and its soil layers
        }
        int floor = Math.max(w.getMinHeight() + 1, j.island.seaY - 140), bottom = Integer.MIN_VALUE;
        boolean changed = false;
        for (int y = limit; y >= floor; y--) {
            Material m = w.getBlockAt(x, y, z).getType();
            if (m.isAir() || HANGING.contains(m) && !m.isOccluding()) continue;
            bottom = y;
            if (!BODY.contains(m)) continue;             // player blocks stay exactly as they are
            set(w, x, y, z, body(j, x, y, z));
            changed = true;
        }
        if (bottom == Integer.MIN_VALUE) return;
        // Replace hanging decorations under the island's bottom.
        for (int y = bottom - 1; y > floor && HANGING.contains(w.getBlockAt(x, y, z).getType()); y--) { set(w, x, y, z, Material.AIR); changed = true; }
        double r = j.noise.random(x, bottom, z, 21);
        int len = 1 + (int) (j.noise.random(x, bottom, z, 22) * 4);
        String[] hang = j.style.hang(r, len);
        if (hang != null) for (int k = 0; k < hang.length; k++) {
            int y = bottom - 1 - k;
            if (!w.getBlockAt(x, y, z).getType().isAir()) break;
            BlockData d = strings.computeIfAbsent(hang[k], s -> { try { return Bukkit.createBlockData(s); } catch (IllegalArgumentException e) { return null; } });
            if (d != null) { w.getBlockAt(x, y, z).setBlockData(d, false); changed = true; }
        }
        if (changed) j.chunks.add(((long) (x >> 4) << 32) | ((z >> 4) & 0xffffffffL));
    }

    private Material body(Job j, int x, int y, int z) {
        double r = j.noise.random(x, y, z, 11);
        double p = j.noise.value(x * 0.09 + y * 0.11, z * 0.09 - y * 0.07);
        boolean nearBottom = !j.world.getBlockAt(x, y - 1, z).getType().isSolid();
        Material m = j.style.body(r, p, nearBottom);
        if (m != null) return m;
        // Rock: stone with patches and ores, like newly generated land.
        if (nearBottom && r < 0.35) return r < 0.15 ? Material.COBBLESTONE : Material.TUFF;
        if (r < 0.006) return Material.COAL_ORE;
        if (r < 0.010) return Material.IRON_ORE;
        if (r < 0.013) return Material.COPPER_ORE;
        if (p > 0.62) return Material.ANDESITE;
        if (p < -0.66) return Material.GRANITE;
        return Material.STONE;
    }

    private void set(World w, int x, int y, int z, Material m) {
        w.getBlockAt(x, y, z).setBlockData(data.computeIfAbsent(m, Material::createBlockData), false);
    }
}
