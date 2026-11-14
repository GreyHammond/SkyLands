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
package dev.skyrealms.economy;

import dev.skyrealms.SkyRealmsPlugin;
import dev.skyrealms.decay.DecaySettings;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Void Shards come out of corrupted land: broken blocks can drop them (more often the worse the
 * corruption), and blocks that decay into bedrock sometimes leave one behind.
 */
public final class CorruptionHarvest implements Listener {
    private static final Set<Material> CORRUPTED_STONE = EnumSet.of(
            Material.BLACKSTONE, Material.DEEPSLATE, Material.COBBLED_DEEPSLATE, Material.BASALT);
    private static final BlockFace[] SIDES = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    private final SkyRealmsPlugin plugin;
    private final boolean enabled;
    private final double chanceDecayed, chanceBlighted, chanceBarren, stoneMult, bedrockMult, crystalChance;
    private final int perMinuteCap;
    /** Block positions players placed, stored per chunk as packed ints so it survives restarts. */
    private final NamespacedKey placedKey;
    /** Shards a player got this minute: {minute, count}. */
    private final Map<UUID, long[]> recent = new HashMap<>();

    public CorruptionHarvest(SkyRealmsPlugin plugin, ConfigurationSection cfg) {
        this.plugin = plugin;
        if (cfg == null) cfg = new MemoryConfiguration();
        enabled = cfg.getBoolean("enabled", true);
        chanceDecayed = clamp(cfg.getDouble("chance.decayed", 0.01));
        chanceBlighted = clamp(cfg.getDouble("chance.blighted", 0.04));
        chanceBarren = clamp(cfg.getDouble("chance.barren", 0.10));
        stoneMult = Math.max(0, cfg.getDouble("corrupted-stone-multiplier", 2.0));
        bedrockMult = Math.max(0, cfg.getDouble("bedrock-adjacent-multiplier", 1.5));
        perMinuteCap = Math.max(0, cfg.getInt("per-minute-cap", 20));
        crystalChance = clamp(cfg.getDouble("bedrock-crystal-chance", 0.02));
        placedKey = new NamespacedKey(plugin, "placed_blocks");
    }

    public void start() {
        if (!enabled) return;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.decay().onBedrock(this::crystallise);
    }

    private boolean inDecayWorld(World w) {
        var d = plugin.decay();
        return d != null && d.isRunning() && w.getName().equals(d.worldName());
    }

    // ---- breaking blocks -----------------------------------------------------------------

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        if (!inDecayWorld(b.getWorld())) return;
        int packed = pack(b);
        PersistentDataContainer pdc = b.getChunk().getPersistentDataContainer();
        int[] arr = pdc.getOrDefault(placedKey, PersistentDataType.INTEGER_ARRAY, new int[0]);
        for (int v : arr) if (v == packed) return;
        if (arr.length >= 4096) arr = Arrays.copyOfRange(arr, arr.length - 4095, arr.length); // bounded; oldest drop off
        int[] next = Arrays.copyOf(arr, arr.length + 1);
        next[arr.length] = packed;
        pdc.set(placedKey, PersistentDataType.INTEGER_ARRAY, next);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!inDecayWorld(b.getWorld())) return;
        boolean placed = removePlaced(b);
        Player p = e.getPlayer();
        if (placed || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        if (b.getType().isAir() || b.getType() == Material.BEDROCK) return;

        double chance = chanceFor(plugin.decay().valueAt(b.getLocation()));
        if (chance <= 0) return;
        if (CORRUPTED_STONE.contains(b.getType())) chance *= stoneMult;
        if (touchesBedrock(b)) chance *= bedrockMult;
        if (ThreadLocalRandom.current().nextDouble() >= chance) return;
        if (!takeFromCap(p)) return;

        Location at = b.getLocation().add(0.5, 0.5, 0.5);
        b.getWorld().dropItemNaturally(at, VoidItems.create(VoidItems.Kind.SHARD, 1));
        b.getWorld().spawnParticle(Particle.REVERSE_PORTAL, at, 12, 0.3, 0.3, 0.3, 0.02);
        b.getWorld().playSound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 0.6f);
    }

    /** Stage start chance, scaling toward the next stage's start chance as corruption climbs. */
    private double chanceFor(double v) {
        DecaySettings s = plugin.decay().settings();
        if (v < s.stage1) return 0;
        if (v < s.stage2) return lerp(chanceDecayed, chanceBlighted, (v - s.stage1) / Math.max(1e-9, s.stage2 - s.stage1));
        if (v < s.stage3) return lerp(chanceBlighted, chanceBarren, (v - s.stage2) / Math.max(1e-9, s.stage3 - s.stage2));
        return lerp(chanceBarren, Math.min(1, chanceBarren * 1.5), (v - s.stage3) / Math.max(1e-9, 100 - s.stage3));
    }

    private boolean touchesBedrock(Block b) {
        for (BlockFace f : SIDES) if (b.getRelative(f).getType() == Material.BEDROCK) return true;
        return false;
    }

    private boolean takeFromCap(Player p) {
        if (perMinuteCap <= 0) return false;
        long minute = System.currentTimeMillis() / 60000L;
        long[] r = recent.computeIfAbsent(p.getUniqueId(), k -> new long[]{minute, 0});
        if (r[0] != minute) { r[0] = minute; r[1] = 0; }
        if (r[1] >= perMinuteCap) return false;
        r[1]++;
        return true;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { recent.remove(e.getPlayer().getUniqueId()); }

    // ---- crystallised corruption -------------------------------------------------------

    private void crystallise(Block bedrock) {
        if (crystalChance <= 0 || ThreadLocalRandom.current().nextDouble() >= crystalChance) return;
        Block above = bedrock.getRelative(BlockFace.UP);
        if (!above.getType().isAir()) return; // only on the surface
        Location at = above.getLocation().add(0.5, 0.2, 0.5);
        bedrock.getWorld().dropItem(at, VoidItems.create(VoidItems.Kind.SHARD, 1));
        bedrock.getWorld().spawnParticle(Particle.REVERSE_PORTAL, at, 8, 0.2, 0.2, 0.2, 0.01);
    }

    // ---- placed-block tracking ---------------------------------------------------------

    private boolean removePlaced(Block b) {
        PersistentDataContainer pdc = b.getChunk().getPersistentDataContainer();
        int[] arr = pdc.get(placedKey, PersistentDataType.INTEGER_ARRAY);
        if (arr == null) return false;
        int packed = pack(b);
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] != packed) continue;
            int[] next = new int[arr.length - 1];
            System.arraycopy(arr, 0, next, 0, i);
            System.arraycopy(arr, i + 1, next, i, arr.length - i - 1);
            if (next.length == 0) pdc.remove(placedKey);
            else pdc.set(placedKey, PersistentDataType.INTEGER_ARRAY, next);
            return true;
        }
        return false;
    }

    /** x and z within the chunk (4 bits each) plus y offset from the world floor (up to 4096 tall). */
    private static int pack(Block b) {
        int y = b.getY() - b.getWorld().getMinHeight();
        return ((b.getX() & 15) << 16) | ((b.getZ() & 15) << 12) | (y & 0xFFF);
    }

    private static double lerp(double a, double b, double t) { return a + (b - a) * Math.max(0, Math.min(1, t)); }

    private static double clamp(double v) { return Math.max(0, Math.min(1, v)); }
}
