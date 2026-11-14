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

import dev.skyrealms.util.Msg;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Overworld decay: players' presence slowly corrupts the chunks around them. State lives in each
 * chunk's persistent data; only chunks that currently have decay are tracked in memory.
 */
public final class DecayService implements Listener {
    private static final double MIN = 0.05;

    private record ChunkPos(int x, int z) {}

    private static final class Cell {
        double value;
        double owed; // fractional block-change attempts carried between ticks
        Cell(double v) { this.value = v; }
    }

    private final Plugin plugin;
    private final DecaySettings s;
    /** The world being corrupted. Starts as decay.world; the overworld recycle can switch it. */
    private volatile String world;
    private final DecayConverter converter;
    private final NamespacedKey key;
    private final Map<ChunkPos, Cell> cells = new HashMap<>();
    private final Map<UUID, double[]> lastPos = new HashMap<>();
    private final Map<UUID, Long> lastActive = new HashMap<>();
    private final List<ChunkPos> convertible = new ArrayList<>();
    private final List<DecayResist> resists = new java.util.concurrent.CopyOnWriteArrayList<>();
    private boolean running;

    public DecayService(Plugin plugin, DecaySettings settings) {
        this.plugin = plugin;
        this.s = settings;
        this.world = settings.world;
        this.converter = new DecayConverter(settings);
        this.key = new NamespacedKey(plugin, "decay");
    }

    public DecaySettings settings() { return s; }

    /** Runs whenever decay turns a block into bedrock. */
    public void onBedrock(java.util.function.Consumer<org.bukkit.block.Block> hook) { converter.onBedrock = hook; }
    public boolean isRunning() { return running; }
    public String worldName() { return world; }
    /** Adds a source of resistance (tonic, gear...). All sources multiply together; each returns 0..1. */
    public void addResist(DecayResist r) { if (r != null) resists.add(r); }

    private double resistFactor(Player p) {
        double f = 1;
        for (DecayResist r : resists) f *= Math.max(0, Math.min(1, r.factor(p)));
        return f;
    }

    /** Registers listeners and starts the tick loops. Returns false (with a log line) if it refuses to run. */
    public boolean start(String skyWorldName) {
        if (!s.enabled) return false;
        World w = Bukkit.getWorld(world);
        if (world.equals(skyWorldName)) {
            plugin.getLogger().severe("decay.world is the sky world. Decay will not run there.");
            return false;
        }
        if (w == null) {
            plugin.getLogger().warning("decay.world '" + world + "' isn't loaded; decay is off until it is.");
            return false;
        }
        if (!s.allowMainWorld && w.equals(Bukkit.getWorlds().get(0))) {
            plugin.getLogger().warning("decay.world '" + world + "' is the server's main world, which can never be "
                    + "recycled. Decay is off. Use a dedicated resource world, or set decay.allow-main-world: true to test.");
            return false;
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Chunk c : w.getLoadedChunks()) load(c);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::convertTick, 20L, 1L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::flush, 1200L, 1200L);
        running = true;
        plugin.getLogger().info("Decay active in '" + world + "'.");
        return true;
    }

    // ---- public API ------------------------------------------------------------------

    public double valueAt(Location l) {
        if (!inWorld(l.getWorld())) return 0;
        Cell c = cells.get(new ChunkPos(l.getBlockX() >> 4, l.getBlockZ() >> 4));
        return c == null ? 0 : c.value;
    }

    public void setAt(Location l, double value) {
        if (!inWorld(l.getWorld())) return;
        ChunkPos p = new ChunkPos(l.getBlockX() >> 4, l.getBlockZ() >> 4);
        double v = Math.max(0, Math.min(100, value));
        if (v < MIN) { cells.remove(p); store(l.getChunk(), null); return; }
        cells.computeIfAbsent(p, k -> new Cell(v)).value = v;
    }

    /**
     * Points decay at a different world (used by the overworld recycle). Tracked values for the
     * old world are dropped, since that world is about to be deleted.
     */
    public void switchWorld(String newWorld) {
        if (newWorld == null || newWorld.equals(world)) return;
        cells.clear();
        convertible.clear();
        lastPos.clear();
        lastActive.clear();
        world = newWorld;
        if (running) {
            World w = Bukkit.getWorld(newWorld);
            if (w != null) for (Chunk c : w.getLoadedChunks()) load(c);
        }
    }

    /** Forgets all decay values (blocks already changed stay changed). */
    public int clearAll() {
        World w = Bukkit.getWorld(world);
        int n = cells.size();
        if (w != null) {
            for (ChunkPos p : cells.keySet()) if (w.isChunkLoaded(p.x, p.z)) store(w.getChunkAt(p.x, p.z), null);
        }
        cells.clear();
        convertible.clear();
        return n;
    }

    /** Writes every tracked value into its chunk. Call on shutdown. */
    public void flush() {
        World w = Bukkit.getWorld(world);
        if (w == null) return;
        for (Map.Entry<ChunkPos, Cell> e : cells.entrySet()) {
            ChunkPos p = e.getKey();
            if (w.isChunkLoaded(p.x, p.z)) store(w.getChunkAt(p.x, p.z), e.getValue());
        }
    }

    // ---- persistence -----------------------------------------------------------------

    private boolean inWorld(World w) { return w != null && w.getName().equals(world); }

    private void load(Chunk c) {
        Float f = c.getPersistentDataContainer().get(key, PersistentDataType.FLOAT);
        if (f != null && f > MIN) cells.put(new ChunkPos(c.getX(), c.getZ()), new Cell(f));
    }

    private void store(Chunk c, Cell cell) {
        var pdc = c.getPersistentDataContainer();
        if (cell == null || cell.value < MIN) pdc.remove(key);
        else pdc.set(key, PersistentDataType.FLOAT, (float) cell.value);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        if (inWorld(e.getWorld())) load(e.getChunk());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) {
        if (!inWorld(e.getWorld())) return;
        Cell c = cells.remove(new ChunkPos(e.getChunk().getX(), e.getChunk().getZ()));
        if (c != null) store(e.getChunk(), c);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastPos.remove(e.getPlayer().getUniqueId());
        lastActive.remove(e.getPlayer().getUniqueId());
    }

    /** Corrupted ground stops growing things and stops grass spreading. */
    @EventHandler(ignoreCancelled = true)
    public void onGrow(BlockGrowEvent e) { if (decayedBlock(e.getBlock())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) { if (decayedBlock(e.getBlock())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent e) { if (decayedBlock(e.getLocation().getBlock())) e.setCancelled(true); }

    private boolean decayedBlock(Block b) {
        if (!inWorld(b.getWorld())) return false;
        Cell c = cells.get(new ChunkPos(b.getX() >> 4, b.getZ() >> 4));
        return c != null && c.value >= s.stage1;
    }

    // ---- the once-a-second pass --------------------------------------------------------

    private Cell cell(World w, ChunkPos p) {
        if (!w.isChunkLoaded(p.x, p.z)) return null;
        return cells.computeIfAbsent(p, k -> new Cell(0));
    }

    private void tick() {
        World w = Bukkit.getWorld(world);
        if (w == null) return;
        long now = System.currentTimeMillis();
        Set<ChunkPos> held = new HashSet<>();

        for (Player p : w.getPlayers()) {
            GameMode gm = p.getGameMode();
            if (gm == GameMode.SPECTATOR || (s.ignoreCreative && gm == GameMode.CREATIVE)) continue;
            Location l = p.getLocation();
            UUID id = p.getUniqueId();
            double[] last = lastPos.get(id);
            if (last == null || sq(last[0] - l.getX()) + sq(last[1] - l.getY()) + sq(last[2] - l.getZ()) >= s.minMove * s.minMove) {
                lastPos.put(id, new double[]{l.getX(), l.getY(), l.getZ()});
                lastActive.put(id, now);
            }
            ChunkPos here = new ChunkPos(l.getBlockX() >> 4, l.getBlockZ() >> 4);
            int reach = s.radius + s.falloff;
            for (int dx = -reach; dx <= reach; dx++) for (int dz = -reach; dz <= reach; dz++) held.add(new ChunkPos(here.x + dx, here.z + dz));

            boolean afk = now - lastActive.getOrDefault(id, now) > s.afkSeconds * 1000L;
            double factor = afk ? 0 : resistFactor(p);
            if (factor > 0) {
                // Full strength out to `radius` chunks, then rings that fade out over `falloff` chunks.
                for (int dx = -reach; dx <= reach; dx++) for (int dz = -reach; dz <= reach; dz++) {
                    Cell n = cell(w, new ChunkPos(here.x + dx, here.z + dz));
                    if (n == null) continue;
                    int d = Math.max(Math.abs(dx), Math.abs(dz));
                    double gain = s.gainPerSecond * factor * (1 + s.ramp * n.value / 100.0);
                    if (d <= s.radius) {
                        n.value = Math.min(100, n.value + gain);
                    } else if (n.value < s.neighborCap) {
                        int ring = d - s.radius; // 1..falloff
                        double strength = s.neighborFraction * (s.falloff - ring + 1) / (double) s.falloff;
                        n.value = Math.min(s.neighborCap, n.value + gain * strength);
                    }
                }
            }
            feedback(p, w, cells.get(here));
        }

        // Quiet chunks recover; barren chunks spill corruption into loaded neighbours (capped, so it can't chain forever).
        List<ChunkPos> keys = new ArrayList<>(cells.keySet());
        for (ChunkPos p : keys) {
            Cell c = cells.get(p);
            if (c == null) continue;
            if (!held.contains(p)) c.value -= s.healPerSecond;
            // Tiny values only get dropped once nobody is adding to them, or a fresh chunk could never get started.
            if (c.value <= 0 || (c.value < MIN && !held.contains(p))) { cells.remove(p); if (w.isChunkLoaded(p.x, p.z)) store(w.getChunkAt(p.x, p.z), null); continue; }
            if (c.value >= s.leakThreshold && s.leakPerSecond > 0) {
                int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                for (int[] d : dirs) {
                    Cell n = cell(w, new ChunkPos(p.x + d[0], p.z + d[1]));
                    if (n != null && n.value < s.leakCap) n.value = Math.min(s.leakCap, n.value + s.leakPerSecond);
                }
            }
        }

        convertible.clear();
        for (Map.Entry<ChunkPos, Cell> e : cells.entrySet()) if (e.getValue().value >= s.stage1) convertible.add(e.getKey());
    }

    private void feedback(Player p, World w, Cell c) {
        if (c == null || c.value < 1) return;
        int stage = DecayConverter.stageOf(s, c.value);
        if (s.actionBar) {
            String color = switch (stage) { case 0 -> "green"; case 1 -> "yellow"; case 2 -> "gold"; default -> "red"; };
            p.sendActionBar(Msg.c("<gray>Corruption <" + color + ">" + (int) c.value + "%</" + color + ">"));
        }
        if (s.particles && stage >= 1) {
            Location l = p.getLocation().add(0, 1, 0);
            w.spawnParticle(Particle.ASH, l, 6 + stage * 8, 4, 2, 4, 0);
            if (stage >= 2 && ThreadLocalRandom.current().nextInt(12) == 0) {
                p.playSound(l, Sound.AMBIENT_SOUL_SAND_VALLEY_MOOD, 0.4f, 0.8f);
            }
        }
    }

    // ---- the every-tick conversion pass ------------------------------------------------

    private void convertTick() {
        if (convertible.isEmpty() || s.attemptsPerSecondMax <= 0) return;
        World w = Bukkit.getWorld(world);
        if (w == null) return;
        converter.beginTick(w);
        Collections.shuffle(convertible);
        int budget = s.maxAttemptsPerTick;
        for (ChunkPos p : convertible) {
            Cell c = cells.get(p);
            if (c == null || !w.isChunkLoaded(p.x, p.z)) continue;
            double rate = s.attemptsPerSecondMax * (c.value / 100.0) * (c.value >= s.stage3 ? s.haste : 1);
            c.owed = Math.min(8, c.owed + rate / 20.0);
            while (c.owed >= 1 && budget > 0) {
                c.owed -= 1;
                budget--;
                converter.attempt(w, p.x, p.z, c.value);
            }
            if (budget <= 0) break;
        }
    }

    private static double sq(double d) { return d * d; }
}
