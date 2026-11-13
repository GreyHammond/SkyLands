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

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Builds floating-island terrain from a list of round "blobs".
 *
 * Shape: each blob is a rounded, domed patch of land with a rocky spike hanging underneath.
 * Overlapping blobs merge into one landmass, and biome heights blend across borders.
 *
 * Sea level: every ocean, shoreline and swamp water block tops out at exactly {@code seaY}.
 * Sea water is only placed where all four sides are held in (land, more water, or existing
 * island); otherwise that column becomes a sand rim, so nothing ever pours into the void.
 * Lakes are the only water above sea level, and only where higher ground rings them.
 *
 * Growth never touches existing columns: a job only fills empty space inside its own blobs
 * and blends its height into the neighbouring land, so player builds are never overwritten.
 */
public final class TerrainGenerator extends BukkitRunnable {

    /** A blob in world coordinates. biome is null for a captured (custom) piece. */
    public record Blob(TerrainBiome biome, double x, double z, double r, UndersideStyle style) {
        public Blob(TerrainBiome biome, double x, double z, double r) { this(biome, x, z, r, UndersideStyle.ROCK); }
    }

    private static final byte NONE = 0, LAND = 1, SEA = 2, LIP = 3, LAKE = 4, LIFT = 5;
    private static final int STITCH = 8;

    private final Plugin plugin;
    private final Logger log;
    private final int seaY;
    private final int blocksPerTick;
    private final Queue<Job> jobs = new ArrayDeque<>();
    private Job current;
    private final Map<Material, BlockData> dataCache = new EnumMap<>(Material.class);
    private final Map<String, BlockData> stringCache = new HashMap<>();

    public TerrainGenerator(Plugin plugin, int seaY, int blocksPerTick) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.seaY = seaY;
        this.blocksPerTick = Math.max(2000, blocksPerTick);
        runTaskTimer(plugin, 3L, 1L);
    }

    public int seaLevel() { return seaY; }

    public int pending() { return jobs.size() + (current == null ? 0 : 1); }

    /**
     * Queue terrain for {@code targets}. {@code all} is every blob on the island (targets included),
     * used for the overall shape. {@code done} runs on the main thread when finished.
     */
    public void generate(World world, long seed, List<Blob> all, List<Blob> targets, boolean hasExistingLand, Runnable done) {
        generate(world, seed, seaY, all, targets, hasExistingLand, done);
    }

    /** As above, for an island whose sea level is {@code islandSeaY}. */
    public void generate(World world, long seed, int islandSeaY, List<Blob> all, List<Blob> targets, boolean hasExistingLand, Runnable done) {
        jobs.add(new Job(world, seed, islandSeaY, List.copyOf(all), List.copyOf(targets), hasExistingLand, done));
    }

    // =========================================================================================
    //  Job
    // =========================================================================================

    private final class Job {
        final World world;
        final long seed;
        /** This island's sea level (hides the generator's default). */
        final int seaY;
        final List<Blob> all, targets;
        final boolean hasOld;
        final Runnable done;
        final Noise noise;

        int minX, minZ, w, h;
        boolean[] existing;
        int[] oldTop;
        /** Existing columns that may be rebuilt so pieces join cleanly: sand rims and plain water. */
        boolean[] replaceable, oldWater, oldLip;
        int[] oldFloor;
        /** Existing plain natural ground (no builds, no trees) that may be raised to meet new land. */
        boolean[] liftable;

        // plan output
        byte[] kind;
        int[] top, bottom, water;
        TerrainBiome[] label;
        UndersideStyle[] style;
        int[] order;                    // target column indices, chunk-sorted
        final List<Object[]> trees = new ArrayList<>();

        CompletableFuture<Void> planning;
        int cursor, treeCursor;
        final Set<Long> chunks = new HashSet<>();

        Job(World world, long seed, int seaY, List<Blob> all, List<Blob> targets, boolean hasOld, Runnable done) {
            this.world = world;
            this.seaY = seaY;
            this.seed = seed;
            this.all = all;
            this.targets = targets;
            this.hasOld = hasOld;
            this.done = done;
            this.noise = new Noise(seed);
        }

        int idx(int x, int z) { return (x - minX) * h + (z - minZ); }

        boolean in(int x, int z) { return x >= minX && x < minX + w && z >= minZ && z < minZ + h; }

        /** Main thread: bounding box + what already exists there. */
        void prepare() {
            double x0 = Double.MAX_VALUE, z0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, z1 = -Double.MAX_VALUE;
            for (Blob b : targets) {
                double reach = b.r * 1.35 + 2;
                x0 = Math.min(x0, b.x - reach); z0 = Math.min(z0, b.z - reach);
                x1 = Math.max(x1, b.x + reach); z1 = Math.max(z1, b.z + reach);
            }
            minX = (int) Math.floor(x0) - STITCH; minZ = (int) Math.floor(z0) - STITCH;
            w = (int) Math.ceil(x1) + STITCH - minX + 1; h = (int) Math.ceil(z1) + STITCH - minZ + 1;
            existing = new boolean[w * h];
            oldTop = new int[w * h];
            replaceable = new boolean[w * h];
            oldWater = new boolean[w * h];
            oldLip = new boolean[w * h];
            oldFloor = new int[w * h];
            liftable = new boolean[w * h];
            if (!hasOld) return;
            for (int x = minX; x < minX + w; x++) {
                for (int z = minZ; z < minZ + h; z++) {
                    if (world.getBlockAt(x, seaY, z).getType().isAir()) continue;
                    int i = idx(x, z);
                    existing[i] = true;
                    oldTop[i] = Math.min(seaY + 40, groundY(x, z));
                    classifyOld(x, z, i);
                    if (!replaceable[i]) liftable[i] = isPlainGround(x, z, oldTop[i]);
                }
            }
        }

        private static final Set<Material> WATER_STUFF = EnumSet.of(Material.WATER, Material.SEAGRASS,
                Material.TALL_SEAGRASS, Material.KELP, Material.KELP_PLANT);
        private static final Set<Material> NATURAL_FLOOR = EnumSet.of(Material.SAND, Material.RED_SAND, Material.GRAVEL,
                Material.CLAY, Material.MUD, Material.SANDSTONE, Material.DIRT, Material.STONE, Material.BASALT);

        private static final Set<Material> GROUND = EnumSet.of(Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT,
                Material.PODZOL, Material.SAND, Material.RED_SAND, Material.MYCELIUM, Material.MOSS_BLOCK, Material.MUD,
                Material.GRAVEL, Material.CALCITE, Material.AMETHYST_BLOCK, Material.SMOOTH_BASALT, Material.BASALT,
                Material.BLACKSTONE, Material.TUFF, Material.MAGMA_BLOCK, Material.MOSSY_COBBLESTONE, Material.ROOTED_DIRT,
                Material.SANDSTONE, Material.STONE, Material.COBBLESTONE, Material.TERRACOTTA, Material.ORANGE_TERRACOTTA,
                Material.YELLOW_TERRACOTTA, Material.WHITE_TERRACOTTA, Material.RED_TERRACOTTA, Material.LIGHT_GRAY_TERRACOTTA,
                Material.BROWN_TERRACOTTA, Material.CLAY);
        private static final Set<Material> SMALL_PLANTS = EnumSet.of(Material.SHORT_GRASS, Material.TALL_GRASS, Material.FERN,
                Material.LARGE_FERN, Material.DANDELION, Material.POPPY, Material.OXEYE_DAISY, Material.CORNFLOWER,
                Material.AZURE_BLUET, Material.ALLIUM, Material.LILY_OF_THE_VALLEY, Material.BLUE_ORCHID, Material.PINK_PETALS,
                Material.SNOW, Material.MOSS_CARPET, Material.DEAD_BUSH, Material.RED_MUSHROOM, Material.BROWN_MUSHROOM,
                Material.SWEET_BERRY_BUSH, Material.AZALEA, Material.FLOWERING_AZALEA, Material.SMALL_DRIPLEAF);

        /** Real ground height: skips tree trunks and leaves, which the height map would count. */
        int groundY(int x, int z) {
            int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            while (y > seaY) {
                Material m = world.getBlockAt(x, y, z).getType();
                String n = m.name();
                if (n.endsWith("_LOG") || n.endsWith("_STEM") || n.endsWith("MUSHROOM_BLOCK") || n.endsWith("_LEAVES")) { y--; continue; }
                break;
            }
            return y;
        }

        /** Plain natural ground we may add blocks on top of: natural top, nothing built above or just below. */
        boolean isPlainGround(int x, int z, int top) {
            if (top <= seaY) return false;
            if (!GROUND.contains(world.getBlockAt(x, top, z).getType())) return false;
            for (int k = 1; k <= 2; k++) if (!GROUND.contains(world.getBlockAt(x, top - k, z).getType())) return false;
            Material a1 = world.getBlockAt(x, top + 1, z).getType(), a2 = world.getBlockAt(x, top + 2, z).getType();
            if (!(a1.isAir() || SMALL_PLANTS.contains(a1))) return false;
            return a2.isAir() || SMALL_PLANTS.contains(a2);
        }

        /** Is this existing column a sand rim or plain sea water we may rebuild? (main thread) */
        void classifyOld(int x, int z, int i) {
            Material above = world.getBlockAt(x, seaY + 1, z).getType();
            if (!above.isAir() && above != Material.LILY_PAD) return;
            Material at = world.getBlockAt(x, seaY, z).getType();
            if (at == Material.SAND || at == Material.RED_SAND) {
                // Generated land always stands at least 2 above sea level, so sand exactly at sea level is a rim.
                replaceable[i] = true; oldLip[i] = true; oldFloor[i] = seaY;
                return;
            }
            if (at != Material.WATER) return;
            int y = seaY;
            while (y > world.getMinHeight() + 1 && WATER_STUFF.contains(world.getBlockAt(x, y, z).getType())) y--;
            if (!NATURAL_FLOOR.contains(world.getBlockAt(x, y, z).getType())) return; // something a player put there
            replaceable[i] = true; oldWater[i] = true; oldFloor[i] = y;
        }

        // ---- planning (async, pure math) ----------------------------------------------------

        double centroidX, centroidZ, extent;

        void plan() {
            double wsum = 0;
            for (Blob b : all) { double a = b.r * b.r; centroidX += b.x * a; centroidZ += b.z * a; wsum += a; }
            centroidX /= wsum; centroidZ /= wsum;
            extent = 1;
            for (Blob b : all) extent = Math.max(extent, Math.hypot(b.x - centroidX, b.z - centroidZ) + b.r);
            int n = w * h;
            kind = new byte[n];
            top = new int[n];
            bottom = new int[n];
            water = new int[n];
            label = new TerrainBiome[n];
            style = new UndersideStyle[n];
            double[] edge = new double[n];
            int[] stitchDist = hasOld ? distanceToExisting() : null;
            int[] stitchTop = hasOld ? nearestExistingTop : null;

            List<Integer> targetCols = new ArrayList<>();
            for (int x = minX; x < minX + w; x++) {
                for (int z = minZ; z < minZ + h; z++) {
                    int i = idx(x, z);
                    if (existing[i] && !replaceable[i] && !liftable[i]) continue;
                    double edgeNoise = noise.fbm(x * 0.05, z * 0.05, 3) * 0.22 + noise.fbm(x * 0.17 + 500, z * 0.17, 2) * 0.07;

                    double mTarget = Double.MAX_VALUE;
                    UndersideStyle colStyle = UndersideStyle.ROCK;
                    for (Blob b : targets) {
                        double d = Math.hypot(x - b.x, z - b.z) / b.r;
                        if (d < mTarget) { mTarget = d; colStyle = b.style == null ? UndersideStyle.ROCK : b.style; }
                    }
                    double reachLimit = !existing[i] ? 1 : oldLip[i] ? 1.6 : liftable[i] ? 1.5 : 1.35;
                    if (mTarget + edgeNoise >= reachLimit) continue;

                    double m = Double.MAX_VALUE, wSum = 0, hSum = 0, bestW = -1, cone = 0;
                    TerrainBiome best = null;
                    int bi = 0;
                    for (Blob b : all) {
                        bi++;
                        double dx = x - b.x, dz = z - b.z;
                        if (Math.abs(dx) > b.r * 2.2 || Math.abs(dz) > b.r * 2.2) continue;
                        double d = Math.hypot(dx, dz) / b.r;
                        m = Math.min(m, d);
                        double c = 1 - d / 1.05;
                        if (c > 0) cone = Math.max(cone, b.r * 1.5 * Math.pow(c, 1.7));
                        if (b.biome == null) continue;
                        double dn = d + noise.fbm(x * 0.045 + bi * 97, z * 0.045 - bi * 53, 2) * 0.28;
                        double wt = Math.exp(-5 * dn * dn);
                        wSum += wt;
                        hSum += wt * biomeHeight(b.biome, x, z);
                        if (wt > bestW) { bestW = wt; best = b.biome; }
                    }
                    if (best == null) { // only captured pieces nearby: borrow the target's biome or plains
                        for (Blob b : targets) if (b.biome != null) { best = b.biome; break; }
                        if (best == null) best = TerrainBiome.PLAINS;
                        hSum = biomeHeight(best, x, z); wSum = 1;
                    }
                    double e = clamp(1 - (m + edgeNoise), 0, 1);
                    double rel = hSum / wSum;

                    if (!best.isWater()) {
                        rel += 7 * smooth(0, 0.45, e);           // rounded top that levels out (no sagging between pieces)
                        rel *= smooth(0, 0.12, e);               // round off toward the rim
                        rel = Math.max(rel, 2);                  // land always stands above the sea
                        if (best == TerrainBiome.BADLANDS && rel > 3) rel = 3 + Math.floor((rel - 3) / 4) * 4;
                    } else {
                        rel *= smooth(0, 0.35, e);               // ocean floor shelves up toward the rim
                    }
                    if (stitchDist != null && stitchDist[i] <= STITCH) {
                        double old = clamp(stitchTop[i] - seaY, -24, 40);
                        rel = old + (rel - old) * smooth(0, 1, stitchDist[i] / (double) STITCH);
                    }

                    int t = seaY + (int) Math.round(rel);
                    if (existing[i] && liftable[i]) {
                        // Raise old ground toward the new surface, tapering off away from the new land. Never lower it.
                        double f = smooth(1.5, 0.95, mTarget + edgeNoise);
                        int lifted = oldTop[i] + (int) Math.floor((t - oldTop[i]) * f);
                        if (lifted <= oldTop[i]) continue;
                        label[i] = best; style[i] = colStyle; edge[i] = 1;
                        top[i] = lifted; bottom[i] = oldTop[i]; kind[i] = LIFT;
                        existing[i] = false;
                        targetCols.add(i);
                        continue;
                    }
                    if (existing[i] && oldWater[i]) {
                        if (t > seaY) continue;                  // never turn existing water into land
                        t = Math.min(t, oldFloor[i]);            // only ever deepen water, never raise its floor
                    }
                    double dc = Math.hypot(x - centroidX, z - centroidZ) / extent;
                    if (dc < 1) cone = Math.max(cone, Math.min(120, extent * 1.15) * Math.pow(1 - dc, 1.3)
                            * (0.85 + 0.3 * noise.fbm(x * 0.08 + 900, z * 0.08, 2)));
                    cone *= 0.12 + 0.88 * smooth(0, 0.55, e);   // undersides curve inward from the rim
                    double thick = 3 + cone + noise.fbm(x * 0.12, z * 0.12, 2) * 2.5
                            + (noise.random(x, 0, z, 7) < 0.05 ? 2 + noise.random(x, 1, z, 8) * 4 : 0);
                    int b = seaY - (int) Math.round(thick);
                    if (b > t - 4) b = t - 4;
                    if (existing[i]) existing[i] = false;        // this column is now being rebuilt
                    b = Math.max(b, world.getMinHeight() + 1);

                    label[i] = best;
                    style[i] = colStyle;
                    top[i] = t;
                    bottom[i] = b;
                    edge[i] = e;
                    kind[i] = t > seaY ? LAND : SEA;
                    targetCols.add(i);
                }
            }

            planLakes(edge);

            // Sea water must be held in on all four sides, otherwise it becomes a sand rim.
            for (int i : targetCols) {
                if (kind[i] != SEA) continue;
                int x = minX + i / h, z = minZ + i % h;
                if (!held(x + 1, z) || !held(x - 1, z) || !held(x, z + 1) || !held(x, z - 1)) {
                    kind[i] = LIP;
                    top[i] = seaY;
                } else {
                    water[i] = seaY;
                }
            }

            // Decorations: trees are placed after all the ground exists.
            for (int i : targetCols) {
                if (kind[i] != LAND || label[i].isWater()) continue;
                if (edge[i] < 0.12 || (stitchDist != null && stitchDist[i] <= 4)) continue;
                int x = minX + i / h, z = minZ + i % h;
                if (nearShore(x, z)) continue;
                TreeType tt = label[i].tree(noise.random(x, top[i], z, 3));
                if (tt != null) trees.add(new Object[]{x, top[i] + 1, z, tt});
            }

            // Build order: chunk by chunk to keep chunk loads tidy.
            order = targetCols.stream().mapToInt(Integer::intValue).boxed()
                    .sorted(Comparator.comparingLong(i -> (((long) ((minX + i / h) >> 4)) << 32) ^ ((minZ + i % h) >> 4)))
                    .mapToInt(Integer::intValue).toArray();
        }

        boolean held(int x, int z) {
            if (!in(x, z)) return false;
            int j = idx(x, z);
            return existing[j] || kind[j] != NONE;
        }

        boolean nearShore(int x, int z) {
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (!in(x + dx, z + dz)) continue;
                byte k = kind[idx(x + dx, z + dz)];
                if (k == SEA || k == LAKE || k == LIP) return true;
            }
            return false;
        }

        int[] nearestExistingTop;

        /** Multi-source BFS from existing columns, giving distance and the nearest existing surface height. */
        int[] distanceToExisting() {
            int n = w * h;
            int[] dist = new int[n];
            nearestExistingTop = new int[n];
            Arrays.fill(dist, Integer.MAX_VALUE);
            ArrayDeque<Integer> q = new ArrayDeque<>();
            for (int i = 0; i < n; i++) if (existing[i] && !replaceable[i] && !liftable[i]) { dist[i] = 0; nearestExistingTop[i] = oldTop[i]; q.add(i); }
            while (!q.isEmpty()) {
                int i = q.poll();
                if (dist[i] >= STITCH) continue;
                int x = i / h, z = i % h;
                int[][] nb = {{x + 1, z}, {x - 1, z}, {x, z + 1}, {x, z - 1}};
                for (int[] c : nb) {
                    if (c[0] < 0 || c[0] >= w || c[1] < 0 || c[1] >= h) continue;
                    int j = c[0] * h + c[1];
                    if (dist[j] != Integer.MAX_VALUE) continue;
                    dist[j] = dist[i] + 1;
                    nearestExistingTop[j] = nearestExistingTop[i];
                    q.add(j);
                }
            }
            return dist;
        }

        /** Lakes: a bowl inside land, filled to just below the lowest point of the ground around it. */
        void planLakes(double[] edge) {
            Random rng = new Random(seed ^ 0x5DEECE66DL);
            for (Blob b : targets) {
                if (b.biome == null || !b.biome.hasLakes() || rng.nextDouble() > 0.4) continue;
                double rl = 3 + rng.nextDouble() * Math.min(6, b.r * 0.3);
                double cx = b.x + (rng.nextDouble() - 0.5) * b.r * 0.6, cz = b.z + (rng.nextDouble() - 0.5) * b.r * 0.6;
                int reach = (int) Math.ceil(rl + 3);
                int ring = Integer.MAX_VALUE;
                boolean ok = true;
                for (int x = (int) cx - reach; x <= (int) cx + reach && ok; x++) {
                    for (int z = (int) cz - reach; z <= (int) cz + reach; z++) {
                        double d = Math.hypot(x - cx, z - cz);
                        if (d > rl + 2.5) continue;
                        if (!in(x, z)) { ok = false; break; }
                        int i = idx(x, z);
                        if (kind[i] != LAND || label[i].isWater() || !label[i].hasLakes() || edge[i] < 0.25) { ok = false; break; }
                        if (d >= rl) ring = Math.min(ring, top[i]);
                    }
                }
                if (!ok || ring == Integer.MAX_VALUE) continue;
                int level = ring - 1;
                if (level < seaY + 2) continue;
                double depth = 2 + rl * 0.5;
                for (int x = (int) cx - reach; x <= (int) cx + reach; x++) {
                    for (int z = (int) cz - reach; z <= (int) cz + reach; z++) {
                        double d = Math.hypot(x - cx, z - cz);
                        if (d >= rl) continue;
                        int i = idx(x, z);
                        int floor = Math.max(seaY + 1, (int) Math.round(Math.min(top[i], level) - depth * (1 - (d / rl) * (d / rl))));
                        if (floor < level) { kind[i] = LAKE; top[i] = floor; water[i] = level; }
                        else top[i] = Math.min(top[i], floor);
                    }
                }
            }
        }

        // ---- building (main thread) ---------------------------------------------------------

        /** Places columns until the budget runs out. Returns blocks used. */
        int build(int budget) {
            int used = 0;
            while (cursor < order.length && used < budget) {
                int i = order[cursor++];
                int x = minX + i / h, z = minZ + i % h;
                used += column(x, z, i);
                chunks.add(((long) (x >> 4) << 32) | ((z >> 4) & 0xffffffffL));
            }
            return used;
        }

        int column(int x, int z, int i) {
            if (kind[i] == LIFT) return lift(x, z, i);
            TerrainBiome lb = label[i];
            int t = top[i], b = bottom[i];
            byte k = kind[i];
            boolean beachy = k == LAND && !lb.isWater() && t <= seaY + 3 && lb != TerrainBiome.SWAMP
                    && lb != TerrainBiome.BADLANDS && seaWithin(x, z, 3);
            int fillDepth = lb.fillDepth();
            int placed = 0;
            for (int y = b; y <= t; y++) {
                int depth = t - y;
                Material m;
                if (depth == 0 && k == LAND && !beachy && !lb.isWater() && lb.surfaceData(noise.random(x, y, z, 1)) != null) {
                    setData(x, y, z, lb.surfaceData(noise.random(x, y, z, 1)));
                    placed++;
                    continue;
                } else if (depth == 0) {
                    m = switch (k) {
                        case LAND -> lb.isWater() || beachy ? Material.SAND : lb.surface(noise.random(x, y, z, 1));
                        case SEA -> lb.isWater() ? lb.surface(noise.random(x, y, z, 1)) : lb.wetFloor(noise.random(x, y, z, 1));
                        case LIP -> lb == TerrainBiome.BADLANDS ? Material.RED_SAND : Material.SAND;
                        case LAKE -> lb == TerrainBiome.VOLCANIC ? Material.BASALT
                                : noise.random(x, y, z, 1) < 0.35 ? Material.CLAY : Material.DIRT;
                        default -> Material.STONE;
                    };
                } else if (depth <= fillDepth && k != LIP) {
                    m = beachy ? (depth <= 2 ? Material.SAND : Material.SANDSTONE) : lb.fill(depth, y);
                } else if (k == LIP && depth <= 2) {
                    m = Material.SAND;
                } else {
                    m = stone(x, y, z, b, style[i]);
                }
                set(x, y, z, m);
                placed++;
            }
            placed += hangings(x, z, b, i);
            if (k == SEA || k == LAKE) {
                Material liquid = k == LAKE ? lb.liquid() : Material.WATER;
                for (int y = t + 1; y <= water[i]; y++) { set(x, y, z, liquid); placed++; }
                waterPlants(x, z, i);
            } else if (k == LAND && !lb.isWater() && !beachy) {
                groundPlant(x, z, i);
            } else if (k == LAND && beachy && lb != TerrainBiome.BEACH && noise.random(x, t, z, 9) < 0.12 && nextToWater(x, z)) {
                int hgt = 1 + (int) (noise.random(x, t, z, 10) * 3);
                for (int y = 1; y <= hgt; y++) set(x, t + y, z, Material.SUGAR_CANE);
            }
            Biome biome = vanilla(lb);
            if (biome != null) {
                int topY = Math.max(t, water[i]) + 20;
                for (int y = b; y <= topY; y += 4) world.setBiome(x, y, z, biome);
            }
            return placed;
        }

        boolean seaWithin(int x, int z, int r) {
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (!in(x + dx, z + dz)) continue;
                byte k = kind[idx(x + dx, z + dz)];
                if (k == SEA || k == LIP) return true;
            }
            return false;
        }

        boolean nextToWater(int x, int z) {
            int[][] nb = {{x + 1, z}, {x - 1, z}, {x, z + 1}, {x, z - 1}};
            for (int[] c : nb) if (in(c[0], c[1]) && kind[idx(c[0], c[1])] == SEA) return true;
            return false;
        }

        /** Raise plain old ground: old top becomes fill, new layers on top, fresh surface and plant. */
        int lift(int x, int z, int i) {
            TerrainBiome lb = label[i];
            int from = bottom[i], t = top[i], placed = 0;
            for (int y = from; y <= t; y++) {
                int depth = t - y;
                if (depth == 0) {
                    String sd = lb.surfaceData(noise.random(x, y, z, 1));
                    if (sd != null) setData(x, y, z, sd); else set(x, y, z, lb.isWater() ? Material.SAND : lb.surface(noise.random(x, y, z, 1)));
                } else {
                    set(x, y, z, lb.fill(Math.min(depth, lb.fillDepth()), y));
                }
                placed++;
            }
            for (int y = t + 1; y <= t + 2; y++) {           // clear old small plants that are now buried or floating
                Material m = world.getBlockAt(x, y, z).getType();
                if (SMALL_PLANTS.contains(m)) set(x, y, z, Material.AIR);
            }
            if (!lb.isWater()) groundPlant(x, z, i);
            chunks.add(((long) (x >> 4) << 32) | ((z >> 4) & 0xffffffffL));
            return placed;
        }

        /** Things hanging below the island's bottom block (style decorations, lush vines). */
        int hangings(int x, int z, int b, int i) {
            double r = noise.random(x, b, z, 21);
            int len = 1 + (int) (noise.random(x, b, z, 22) * 4);
            String[] hang = style[i].hang(r, len);
            if (hang == null && label[i] == TerrainBiome.LUSH_CAVERNS && r > 0.94) hang = UndersideStyle.vines(len);
            if (hang == null) return 0;
            int n = 0;
            for (int k = 0; k < hang.length; k++) {
                int y = b - 1 - k;
                if (y <= world.getMinHeight() || !world.getBlockAt(x, y, z).getType().isAir()) break;
                setData(x, y, z, hang[k]);
                n++;
            }
            return n;
        }

        Material stone(int x, int y, int z, int b, UndersideStyle st) {
            double r = noise.random(x, y, z, 11);
            if (st != null && st != UndersideStyle.ROCK) {
                double pp = noise.value(x * 0.09 + y * 0.11, z * 0.09 - y * 0.07);
                Material m = st.body(r, pp, y < b + 2);
                if (m != null) return m;
            }
            if (y < b + 2 && r < 0.35) return r < 0.15 ? Material.COBBLESTONE : Material.TUFF;
            if (r < 0.006) return Material.COAL_ORE;
            if (r < 0.010) return Material.IRON_ORE;
            if (r < 0.013) return Material.COPPER_ORE;
            if (r < 0.0138 && y < seaY - 8) return Material.GOLD_ORE;
            if (r < 0.0148 && y < seaY - 8) return Material.REDSTONE_ORE;
            if (r < 0.0153 && y < seaY - 12) return Material.LAPIS_ORE;
            if (r < 0.0156 && y < seaY - 20) return Material.DIAMOND_ORE;
            double p = noise.value(x * 0.09 + y * 0.11, z * 0.09 - y * 0.07);
            if (p > 0.62) return Material.ANDESITE;
            if (p < -0.66) return Material.GRANITE;
            return Material.STONE;
        }

        void groundPlant(int x, int z, int i) {
            String plant = label[i].plant(noise.random(x, top[i], z, 4));
            if (plant == null) return;
            int y = top[i] + 1;
            if (plant.equals("pillar")) {                 // broken ancient pillar
                int hgt = 2 + (int) (noise.random(x, y, z, 5) * 4);
                for (int k = 0; k < hgt; k++) {
                    double rr = noise.random(x, y + k, z, 13);
                    set(x, y + k, z, k == 0 && rr < 0.5 ? Material.CHISELED_STONE_BRICKS
                            : rr < 0.35 ? Material.MOSSY_STONE_BRICKS : rr < 0.6 ? Material.CRACKED_STONE_BRICKS : Material.STONE_BRICKS);
                }
                if (noise.random(x, y, z, 14) < 0.5) setData(x, y + hgt, z, "minecraft:stone_brick_slab[type=bottom]");
                return;
            }
            if (plant.equals("rubble")) {
                double rr = noise.random(x, y, z, 15);
                setData(x, y, z, rr < 0.4 ? "minecraft:mossy_stone_brick_slab[type=bottom]" : rr < 0.7 ? "minecraft:mossy_cobblestone"
                        : "minecraft:stone_brick_stairs[facing=" + (rr < 0.85 ? "north" : "east") + "]");
                return;
            }
            if (plant.equals("spire")) {                  // crystal spire
                int hgt = 2 + (int) (noise.random(x, y, z, 5) * 4);
                for (int k = 0; k < hgt; k++) set(x, y + k, z, noise.random(x, y + k, z, 16) < 0.25 ? Material.CALCITE : Material.AMETHYST_BLOCK);
                setData(x, y + hgt, z, "minecraft:amethyst_cluster[facing=up]");
                return;
            }
            if (plant.equals("cactus")) {
                int hgt = 1 + (int) (noise.random(x, y, z, 5) * 3);
                for (int k = 0; k < hgt; k++) set(x, y + k, z, Material.CACTUS);
                return;
            }
            String[] parts = plant.split("\\|");
            setData(x, y, z, parts[0]);
            if (parts.length > 1) setData(x, y + 1, z, parts[1]);
        }

        void waterPlants(int x, int z, int i) {
            int floor = top[i], surface = water[i], depth = surface - floor;
            if (depth < 1) return;
            TerrainBiome lb = label[i];
            double r = noise.random(x, floor, z, 6);
            if (lb == TerrainBiome.SWAMP && kind[i] == SEA) {
                if (r < 0.14) set(x, surface + 1, z, Material.LILY_PAD);
                else if (r < 0.22) set(x, floor + 1, z, Material.SEAGRASS);
                return;
            }
            if ((lb == TerrainBiome.DEEP_OCEAN && r < 0.07) || (lb == TerrainBiome.SHALLOW_OCEAN && r < 0.035)) {
                int len = Math.max(1, (int) ((depth - 2) * (0.4 + noise.random(x, floor, z, 12) * 0.6)));
                for (int y = floor + 1; y < floor + len; y++) set(x, y, z, Material.KELP_PLANT);
                set(x, floor + len, z, Material.KELP);
            } else if (r < 0.25 && depth >= 3) {
                setData(x, floor + 1, z, "minecraft:tall_seagrass[half=lower]");
                setData(x, floor + 2, z, "minecraft:tall_seagrass[half=upper]");
            } else if (r < 0.45 && depth >= 2) {
                set(x, floor + 1, z, Material.SEAGRASS);
            }
        }

        void set(int x, int y, int z, Material m) {
            world.getBlockAt(x, y, z).setBlockData(dataCache.computeIfAbsent(m, Material::createBlockData), false);
        }

        void setData(int x, int y, int z, String data) {
            BlockData d = stringCache.computeIfAbsent(data, s -> {
                try { return Bukkit.createBlockData(s); } catch (IllegalArgumentException e) { return null; }
            });
            if (d != null) world.getBlockAt(x, y, z).setBlockData(d, false);
        }

        /** Grows a few trees. Returns how many it tried. */
        int growTrees(int max) {
            Random r = new Random(seed + treeCursor);
            int n = 0;
            while (treeCursor < trees.size() && n < max) {
                Object[] t = trees.get(treeCursor++);
                world.generateTree(new Location(world, (int) t[0], (int) t[1], (int) t[2]), r, (TreeType) t[3]);
                n++;
            }
            return n;
        }

        /**
         * Healing pass: sand rims left between bodies of water by earlier growth become water.
         * A rim block turns to water only if all four sides are held (water or solid) and at least
         * two are water; it's carved no deeper than its shallowest water neighbour, so it stays held in.
         */
        void healSeams() {
            for (int pass = 0; pass < 4; pass++) {
                int changed = 0;
                for (int x = minX; x < minX + w; x++) {
                    for (int z = minZ; z < minZ + h; z++) {
                        Material at = world.getBlockAt(x, seaY, z).getType();
                        if (at != Material.SAND && at != Material.RED_SAND) continue;
                        Material above = world.getBlockAt(x, seaY + 1, z).getType();
                        if (!above.isAir() && above != Material.LILY_PAD) continue;
                        int waterNb = 0, depth = Integer.MAX_VALUE;
                        boolean open = false;
                        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                            Material m = world.getBlockAt(x + d[0], seaY, z + d[1]).getType();
                            if (m.isAir()) { open = true; break; }
                            if (m == Material.WATER) {
                                waterNb++;
                                int dd = 0;
                                while (dd < 40 && world.getBlockAt(x + d[0], seaY - dd, z + d[1]).getType() == Material.WATER) dd++;
                                depth = Math.min(depth, dd);
                            }
                        }
                        if (open || waterNb < 2 || depth == Integer.MAX_VALUE) continue;
                        for (int k = 0; k < depth; k++) set(x, seaY - k, z, Material.WATER);
                        chunks.add(((long) (x >> 4) << 32) | ((z >> 4) & 0xffffffffL));
                        changed++;
                    }
                }
                if (changed == 0) break;
            }
        }

        boolean built() { return cursor >= order.length; }

        boolean treesDone() { return treeCursor >= trees.size(); }
    }

    // =========================================================================================
    //  Shared helpers
    // =========================================================================================

    private double biomeHeight(TerrainBiome b, double x, double z) {
        return b.base + b.amp * heightNoise.fbm(x * b.scale + b.ordinal() * 1013, z * b.scale - b.ordinal() * 733, 4);
    }

    private final Noise heightNoise = new Noise(0x5EA1EAFL);
    private final Map<TerrainBiome, Biome> vanillaCache = new EnumMap<>(TerrainBiome.class);

    private Biome vanilla(TerrainBiome b) {
        return vanillaCache.computeIfAbsent(b, k -> {
            NamespacedKey key = NamespacedKey.fromString(k.vanillaBiome);
            return key == null ? null : RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME).get(key);
        });
    }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    private static double smooth(double a, double b, double v) {
        double t = clamp((v - a) / (b - a), 0, 1);
        return t * t * (3 - 2 * t);
    }

    // =========================================================================================
    //  Runner
    // =========================================================================================

    @Override
    public void run() {
        if (current == null) {
            current = jobs.poll();
            if (current == null) return;
            Job j = current;
            j.prepare();
            j.planning = CompletableFuture.runAsync(j::plan);
        }
        Job j = current;
        if (!j.planning.isDone()) return;
        if (j.planning.isCompletedExceptionally()) {
            j.planning.exceptionally(ex -> { log.severe("Island terrain planning failed: " + ex); return null; });
            current = null;
            return;
        }
        if (!j.built()) {
            j.build(blocksPerTick);
            return;
        }
        if (!j.treesDone()) {
            j.growTrees(40);
            return;
        }
        if (j.hasOld) j.healSeams();
        for (long key : j.chunks) j.world.refreshChunk((int) (key >> 32), (int) key);
        current = null;
        if (j.done != null) {
            try { j.done.run(); } catch (Exception e) { log.warning("Island callback failed: " + e); }
        }
    }
}
