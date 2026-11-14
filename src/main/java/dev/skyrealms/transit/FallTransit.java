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
package dev.skyrealms.transit;

import dev.skyrealms.util.Msg;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Jump off an island -> appear high above the overworld, roughly under where you jumped,
 * and keep falling. The landing that ends this fall takes no damage; nothing else is protected.
 */
public final class FallTransit implements Listener {
    private static final Set<Material> UNSAFE = EnumSet.of(
            Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.MAGMA_BLOCK, Material.CACTUS,
            Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
            Material.POINTED_DRIPSTONE, Material.WITHER_ROSE);

    private final Plugin plugin;
    private final String skyWorld;
    /** Read on every fall, so the overworld recycle can switch the target world at runtime. */
    private final java.util.function.Supplier<String> overworld;
    private final double departY, arriveY, maxOffset, closenessBias, minArriveSpeed;
    private final int rerolls;
    private final long protectionMillis;
    /** Outward search for dry land when every nearby roll lands in water. */
    private final int landSearchRadius, landSearchStep, landSearchMaxChecks;

    /** Water, kelp and the like on top of a column: an ocean or a lake, not ground. */
    private static final Set<Material> WATERY = EnumSet.of(
            Material.WATER, Material.BUBBLE_COLUMN, Material.KELP, Material.KELP_PLANT,
            Material.SEAGRASS, Material.TALL_SEAGRASS);

    /** A chosen landing spot, and whether it's on dry ground. */
    private record Landing(Location loc, boolean dry) {}

    /** Players whose fall found no land: they get a boat when they hit the water. */
    private final Set<UUID> boatFalls = new HashSet<>();

    /** Players between "fell off" and "teleported". */
    private final Set<UUID> departing = new HashSet<>();
    /** Players falling into the overworld with landing protection -> expiry time. */
    private final Map<UUID, Long> protectedFalls = new HashMap<>();
    /** Consecutive on-ground checks, so we don't drop protection a tick before the damage event. */
    private final Map<UUID, Integer> groundedChecks = new HashMap<>();

    public FallTransit(Plugin plugin, String skyWorld, java.util.function.Supplier<String> overworld, ConfigurationSection cfg) {
        this.plugin = plugin;
        this.skyWorld = skyWorld;
        this.overworld = overworld;
        this.departY = cfg.getDouble("depart-y", -40);
        this.arriveY = cfg.getDouble("arrive-y", 310);
        this.maxOffset = Math.max(0, cfg.getDouble("max-landing-offset", 200));
        this.closenessBias = Math.max(0.1, cfg.getDouble("landing-closeness-bias", 1.6));
        this.rerolls = Math.max(0, cfg.getInt("reroll-unsafe-landings", 8));
        this.minArriveSpeed = Math.abs(cfg.getDouble("min-arrive-speed", 1.0));
        this.protectionMillis = cfg.getLong("protection-seconds", 90) * 1000L;
        this.landSearchRadius = Math.max(0, cfg.getInt("land-search-radius", 1000));
        this.landSearchStep = Math.max(16, cfg.getInt("land-search-step", 64));
        this.landSearchMaxChecks = Math.max(0, cfg.getInt("land-search-max-checks", 120));

        Bukkit.getScheduler().runTaskTimer(plugin, this::tickProtection, 10L, 10L);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent e) {
        Location to = e.getTo();
        if (to.getY() >= departY || e.getFrom().getY() < to.getY()) return;
        if (!to.getWorld().getName().equals(skyWorld)) return;
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.SPECTATOR || p.isFlying()) return;
        if (!departing.add(p.getUniqueId())) return;

        String groundName = overworld.get();
        World ground = Bukkit.getWorld(groundName);
        if (ground == null) {
            departing.remove(p.getUniqueId());
            Msg.error(p, "The overworld <white>" + groundName + "</white> isn't loaded. Tell an admin.");
            return;
        }
        findLanding(ground, to.getX(), to.getZ()).thenAccept(found -> {
            if (!p.isOnline()) { departing.remove(p.getUniqueId()); return; }
            Location landing = found.loc();
            Vector momentum = p.getVelocity();
            landing.setYaw(p.getYaw());
            landing.setPitch(p.getPitch());
            p.teleportAsync(landing, PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept(ok -> {
                departing.remove(p.getUniqueId());
                if (!ok) return;
                protectedFalls.put(p.getUniqueId(), System.currentTimeMillis() + protectionMillis);
                if (found.dry()) boatFalls.remove(p.getUniqueId()); else boatFalls.add(p.getUniqueId());
                if (plugin instanceof dev.skyrealms.SkyRealmsPlugin sr && sr.milestones() != null)
                    sr.milestones().trigger(p, dev.skyrealms.player.Milestone.LANDED_OVERWORLD);
                groundedChecks.remove(p.getUniqueId());
                p.setFallDistance(0);
                // Next tick, keep their sideways momentum and make sure they're heading down.
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Vector v = momentum.clone();
                    v.setY(Math.min(v.getY(), -minArriveSpeed));
                    p.setVelocity(v);
                });
                p.playSound(p.getLocation(), Sound.ITEM_ELYTRA_FLYING, 0.6f, 1.2f);
            });
        });
    }

    /**
     * Picks where a fall lands: random spots near (x, z) first; if every one is water or otherwise
     * unsafe, rings of widening radius for the nearest dry land; failing that, straight below (x, z).
     * Every chunk is loaded off the main thread.
     */
    private CompletableFuture<Landing> findLanding(World w, double x, double z) {
        return randomRoll(w, x, z, rerolls).thenCompose(hit -> {
            if (hit != null) return CompletableFuture.completedFuture(new Landing(hit, true));
            return ringSearch(w, x, z, rings(w, x, z), 0, landSearchMaxChecks).thenCompose(ring -> {
                if (ring != null) return CompletableFuture.completedFuture(new Landing(ring, true));
                // Nothing dry in range: land straight below, and leave them a boat.
                int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
                return w.getChunkAtAsync(bx >> 4, bz >> 4).thenApply(c -> new Landing(arrival(w, x, z, w.getHighestBlockAt(bx, bz)), false));
            });
        });
    }

    /** Random spots within max-landing-offset; completes with null once all tries are unsafe. */
    private CompletableFuture<Location> randomRoll(World w, double x, double z, int triesLeft) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double angle = r.nextDouble(Math.PI * 2);
        double dist = maxOffset * Math.pow(r.nextDouble(), closenessBias);
        double lx = x + Math.cos(angle) * dist;
        double lz = z + Math.sin(angle) * dist;
        if (!w.getWorldBorder().isInside(new Location(w, lx, 0, lz))) { lx = x; lz = z; }
        final double fx = lx, fz = lz;
        return w.getChunkAtAsync((int) Math.floor(fx) >> 4, (int) Math.floor(fz) >> 4).thenCompose(chunk -> {
            Block top = w.getHighestBlockAt((int) Math.floor(fx), (int) Math.floor(fz));
            if (safe(top)) return CompletableFuture.completedFuture(arrival(w, fx, fz, top));
            if (triesLeft > 0) return randomRoll(w, x, z, triesLeft - 1);
            return CompletableFuture.completedFuture((Location) null);
        });
    }

    /** Sample points on rings around (x, z), out to land-search-radius, grouped ring by ring. */
    private List<List<double[]>> rings(World w, double x, double z) {
        List<List<double[]>> out = new ArrayList<>();
        WorldBorder border = w.getWorldBorder();
        for (int radius = landSearchStep; radius <= landSearchRadius; radius += landSearchStep) {
            int n = Math.max(8, Math.min(16, (int) Math.round(2 * Math.PI * radius / landSearchStep)));
            double start = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
            List<double[]> ring = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                double a = start + i * 2 * Math.PI / n;
                double px = x + Math.cos(a) * radius, pz = z + Math.sin(a) * radius;
                if (border.isInside(new Location(w, px, 0, pz))) ring.add(new double[]{px, pz});
            }
            if (!ring.isEmpty()) out.add(ring);
        }
        return out;
    }

    /**
     * Checks one ring at a time (its points loaded together), nearest ring first, and completes with
     * the first dry spot, or null when the rings or the check budget run out.
     */
    private CompletableFuture<Location> ringSearch(World w, double x, double z, List<List<double[]>> rings, int index, int budget) {
        if (index >= rings.size() || budget <= 0) return CompletableFuture.completedFuture(null);
        List<double[]> ring = rings.get(index);
        if (ring.size() > budget) ring = ring.subList(0, budget);
        List<double[]> pts = ring;
        List<CompletableFuture<Location>> checks = new ArrayList<>(pts.size());
        for (double[] pt : pts) {
            int bx = (int) Math.floor(pt[0]), bz = (int) Math.floor(pt[1]);
            checks.add(w.getChunkAtAsync(bx >> 4, bz >> 4).thenApply(c -> {
                Block top = w.getHighestBlockAt(bx, bz);
                return safe(top) ? arrival(w, pt[0], pt[1], top) : null;
            }));
        }
        int spent = pts.size();
        return CompletableFuture.allOf(checks.toArray(new CompletableFuture[0])).thenCompose(v -> {
            for (CompletableFuture<Location> c : checks) {
                Location hit = c.join();
                if (hit != null) return CompletableFuture.completedFuture(hit);
            }
            return ringSearch(w, x, z, rings, index + 1, budget - spent);
        });
    }

    /** Dry, solid ground that isn't lava, cactus or the like. */
    private static boolean safe(Block top) {
        Material m = top.getType();
        if (m.isAir() || UNSAFE.contains(m) || WATERY.contains(m) || Tag.ICE.isTagged(m)) return false;
        if (top.getBlockData() instanceof Waterlogged wl && wl.isWaterlogged()) return false;
        String biome = top.getBiome().getKey().getKey();
        return !biome.contains("ocean") && !biome.contains("river");
    }

    /** High above the chosen spot, always with some sky to fall through. */
    private Location arrival(World w, double x, double z, Block top) {
        double y = Math.min(arriveY, w.getMaxHeight() - 2);
        y = Math.max(y, top.getY() + 40);
        return new Location(w, x, y, z);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        UUID id = p.getUniqueId();
        // Don't let the sky world's void hurt someone mid-transfer.
        if (e.getCause() == EntityDamageEvent.DamageCause.VOID && departing.contains(id)) {
            e.setCancelled(true);
            return;
        }
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL && protectedFalls.remove(id) != null) {
            e.setCancelled(true);
            p.stopSound(Sound.ITEM_ELYTRA_FLYING);
            groundedChecks.remove(id);
            p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 20, 0.4, 0.1, 0.4, 0.02);
        }
    }

    /** Protection ends on its own when they land softly (water, elytra) or it times out. */
    private void tickProtection() {
        long now = System.currentTimeMillis();
        // A fall that found no land: hand over a boat the moment they hit the water.
        for (Iterator<UUID> bi = boatFalls.iterator(); bi.hasNext(); ) {
            UUID id = bi.next();
            Player bp = Bukkit.getPlayer(id);
            if (bp == null || !protectedFalls.containsKey(id)) { bi.remove(); continue; }
            if (!bp.isInWater()) continue;
            bi.remove();
            for (var left : bp.getInventory().addItem(new org.bukkit.inventory.ItemStack(Material.OAK_BOAT)).values()) {
                bp.getWorld().dropItemNaturally(bp.getLocation(), left);
            }
            Msg.send(bp, "No land in sight. The winds left you a boat.");
        }
        Iterator<Map.Entry<UUID, Long>> it = protectedFalls.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p != null && now > entry.getValue()) p.stopSound(Sound.ITEM_ELYTRA_FLYING);
            if (p == null || now > entry.getValue()) { it.remove(); groundedChecks.remove(entry.getKey()); continue; }
            @SuppressWarnings("deprecation") boolean grounded = p.isOnGround() || p.isInWater();
            if (grounded && !p.isGliding()) {
                p.stopSound(Sound.ITEM_ELYTRA_FLYING);           // the wind ends the moment you touch down
                int n = groundedChecks.merge(entry.getKey(), 1, Integer::sum);
                if (n >= 2) { it.remove(); groundedChecks.remove(entry.getKey()); }
            } else {
                groundedChecks.remove(entry.getKey());
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        departing.remove(id);
        // Keep boatFalls too: someone who rejoins mid-fall still gets the boat if they land in water.
        // Keep protectedFalls: if they log out mid-fall they'll still land safely on rejoin (until expiry).
        groundedChecks.remove(id);
    }
}
