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
        findLanding(ground, to.getX(), to.getZ(), rerolls).thenAccept(landing -> {
            if (!p.isOnline()) { departing.remove(p.getUniqueId()); return; }
            Vector momentum = p.getVelocity();
            landing.setYaw(p.getYaw());
            landing.setPitch(p.getPitch());
            p.teleportAsync(landing, PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept(ok -> {
                departing.remove(p.getUniqueId());
                if (!ok) return;
                protectedFalls.put(p.getUniqueId(), System.currentTimeMillis() + protectionMillis);
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

    /** Picks a random spot near (x, z), loads its chunk off-thread, and re-rolls unsafe surfaces. */
    private java.util.concurrent.CompletableFuture<Location> findLanding(World w, double x, double z, int triesLeft) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double angle = r.nextDouble(Math.PI * 2);
        double dist = maxOffset * Math.pow(r.nextDouble(), closenessBias);
        double lx = x + Math.cos(angle) * dist;
        double lz = z + Math.sin(angle) * dist;
        WorldBorder border = w.getWorldBorder();
        Location candidate = new Location(w, lx, 0, lz);
        if (!border.isInside(candidate)) { lx = x; lz = z; }
        final double fx = lx, fz = lz;
        return w.getChunkAtAsync((int) Math.floor(fx) >> 4, (int) Math.floor(fz) >> 4).thenCompose(chunk -> {
            Block top = w.getHighestBlockAt((int) Math.floor(fx), (int) Math.floor(fz));
            boolean unsafe = UNSAFE.contains(top.getType()) || top.getType().isAir();
            if (unsafe && triesLeft > 0) return findLanding(w, x, z, triesLeft - 1);
            double y = Math.min(arriveY, w.getMaxHeight() - 2);
            y = Math.max(y, top.getY() + 40); // always arrive with some sky to fall through
            return java.util.concurrent.CompletableFuture.completedFuture(new Location(w, fx, y, fz));
        });
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
        // Keep protectedFalls: if they log out mid-fall they'll still land safely on rejoin (until expiry).
        groundedChecks.remove(id);
    }
}
