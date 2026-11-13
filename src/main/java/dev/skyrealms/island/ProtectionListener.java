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

import dev.skyrealms.util.Msg;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.projectiles.ProjectileSource;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Enforces each island's flags in the sky world. Owners can do anything; friends and
 * visitors get whatever the owner allowed. Outside any island nobody can change anything.
 */
public final class ProtectionListener implements Listener {
    private final IslandManager islands;
    private final String skyWorld;
    private final Map<UUID, Long> lastWarning = new HashMap<>();

    public ProtectionListener(IslandManager islands, String skyWorld) {
        this.islands = islands;
        this.skyWorld = skyWorld;
    }

    private boolean inSky(World w) { return w != null && w.getName().equals(skyWorld); }

    private Island islandAt(Location loc) { return islands.at(loc.getX(), loc.getZ()); }

    /** Cancels and warns if the player lacks the flag here. Returns true if denied. */
    private boolean deny(Player p, Location loc, Flag flag, Cancellable e) {
        if (flag == null || loc == null || !inSky(loc.getWorld())) return false;
        if (p.hasPermission("skyrealms.admin.bypass")) return false;
        Island i = islandAt(loc);
        if (i != null && i.allows(p.getUniqueId(), flag)) return false;
        e.setCancelled(true);
        long now = System.currentTimeMillis();
        Long last = lastWarning.get(p.getUniqueId());
        if (last == null || now - last > 1500) {
            lastWarning.put(p.getUniqueId(), now);
            p.sendActionBar(Msg.c(i == null ? "<red>You can't do that out here."
                    : "<red>The owner hasn't allowed <white>" + flag.title.toLowerCase() + "</white> here."));
        }
        return true;
    }

    private boolean settingOff(Location loc, Flag setting) {
        if (!inSky(loc.getWorld())) return false;
        Island i = islandAt(loc);
        return i == null || !i.setting(setting);
    }

    private static Player playerFrom(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj) {
            ProjectileSource src = proj.getShooter();
            if (src instanceof Player p) return p;
        }
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player p) return p;
        return null;
    }

    // ---- blocks -------------------------------------------------------------

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onBreak(BlockBreakEvent e) { deny(e.getPlayer(), e.getBlock().getLocation(), BlockRules.forBreak(e.getBlock()), e); }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onPlace(BlockPlaceEvent e) { deny(e.getPlayer(), e.getBlock().getLocation(), BlockRules.forPlace(e.getBlock()), e); }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onMultiPlace(BlockMultiPlaceEvent e) { deny(e.getPlayer(), e.getBlock().getLocation(), Flag.PLACE, e); }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) { deny(e.getPlayer(), e.getBlock().getLocation(), Flag.BUCKETS, e); }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onBucketFill(PlayerBucketFillEvent e) { deny(e.getPlayer(), e.getBlock().getLocation(), Flag.BUCKETS, e); }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onBucketEntity(PlayerBucketEntityEvent e) { deny(e.getPlayer(), e.getEntity().getLocation(), Flag.ANIMALS, e); }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onSignEdit(SignChangeEvent e) { deny(e.getPlayer(), e.getBlock().getLocation(), Flag.OTHER, e); }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onFertilize(BlockFertilizeEvent e) {
        if (e.getPlayer() != null) deny(e.getPlayer(), e.getBlock().getLocation(), Flag.PLACE, e);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null || !inSky(b.getWorld())) return;
        Player p = e.getPlayer();
        switch (e.getAction()) {
            case PHYSICAL -> deny(p, b.getLocation(), BlockRules.forPhysical(b.getType()), e);
            case RIGHT_CLICK_BLOCK -> {
                Flag f = BlockRules.forInteract(b);
                // Sneaking with a block in hand places instead of opening; let BlockPlaceEvent decide.
                if (f != null && p.isSneaking() && e.getItem() != null && e.getItem().getType().isBlock()) f = null;
                if (f == null && e.getItem() != null && BlockRules.modifiesBlock(e.getItem().getType())) f = Flag.PLACE;
                deny(p, b.getLocation(), f, e);
            }
            default -> { }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onTakeLecternBook(PlayerTakeLecternBookEvent e) { deny(e.getPlayer(), e.getLectern().getLocation(), Flag.CONTAINERS, e); }

    // ---- entities -----------------------------------------------------------

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND && e.getHand() != EquipmentSlot.OFF_HAND) return;
        deny(e.getPlayer(), e.getRightClicked().getLocation(), BlockRules.forEntityInteract(e.getRightClicked()), e);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) { deny(e.getPlayer(), e.getRightClicked().getLocation(), Flag.DECOR, e); }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onLeash(PlayerLeashEntityEvent e) { deny(e.getPlayer(), e.getEntity().getLocation(), Flag.ANIMALS, e); }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onShear(PlayerShearEntityEvent e) { deny(e.getPlayer(), e.getEntity().getLocation(), Flag.ANIMALS, e); }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onDamageEntity(EntityDamageByEntityEvent e) {
        Player p = playerFrom(e.getDamager());
        if (p == null || !inSky(e.getEntity().getWorld())) return;
        if (e.getEntity() instanceof Player victim && !victim.equals(p)) {
            if (settingOff(victim.getLocation(), Flag.PVP)) e.setCancelled(true);
            return;
        }
        deny(p, e.getEntity().getLocation(), BlockRules.forEntityDamage(e.getEntity()), e);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onHangingBreak(HangingBreakByEntityEvent e) {
        Player p = playerFrom(e.getRemover());
        if (p != null) deny(p, e.getEntity().getLocation(), Flag.DECOR, e);
        else if (e.getRemover() != null && settingOff(e.getEntity().getLocation(), Flag.MOB_GRIEF)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onHangingPlace(HangingPlaceEvent e) {
        if (e.getPlayer() != null) deny(e.getPlayer(), e.getEntity().getLocation(), Flag.DECOR, e);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onEntityPlace(EntityPlaceEvent e) {
        if (e.getPlayer() != null) deny(e.getPlayer(), e.getEntity().getLocation(), Flag.PLACE, e);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onVehicleDamage(VehicleDamageEvent e) {
        Player p = playerFrom(e.getAttacker());
        if (p != null) deny(p, e.getVehicle().getLocation(), Flag.BREAK, e);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onVehicleEnter(VehicleEnterEvent e) {
        if (e.getEntered() instanceof Player p) deny(p, e.getVehicle().getLocation(), Flag.RIDE, e);
    }

    // ---- island-wide settings ---------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (inSky(e.getLocation().getWorld())) e.blockList().removeIf(b -> settingOff(b.getLocation(), Flag.EXPLOSIONS));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (inSky(e.getBlock().getWorld())) e.blockList().removeIf(b -> settingOff(b.getLocation(), Flag.EXPLOSIONS));
    }

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (e.getCause() == BlockIgniteEvent.IgniteCause.SPREAD || e.getCause() == BlockIgniteEvent.IgniteCause.LAVA
                || e.getCause() == BlockIgniteEvent.IgniteCause.LIGHTNING) {
            if (settingOff(e.getBlock().getLocation(), Flag.FIRE_SPREAD)) e.setCancelled(true);
        } else if (e.getPlayer() != null) {
            deny(e.getPlayer(), e.getBlock().getLocation(), Flag.PLACE, e);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (settingOff(e.getBlock().getLocation(), Flag.FIRE_SPREAD)) e.setCancelled(true);
    }

    /** Island-wide natural spawning toggles. Spawn eggs, breeding, spawners and commands always work. */
    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!inSky(e.getLocation().getWorld())) return;
        switch (e.getSpawnReason()) {
            case NATURAL, JOCKEY, CHUNK_GEN, PATROL, MOUNT, REINFORCEMENTS, VILLAGE_INVASION, RAID, TRAP -> { }
            default -> { return; }
        }
        Island i = islandAt(e.getLocation());
        if (i == null) return;
        boolean hostile = e.getEntity() instanceof Enemy;
        if (hostile ? !i.setting(Flag.MONSTER_SPAWNS) : !i.setting(Flag.ANIMAL_SPAWNS)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMobChangeBlock(EntityChangeBlockEvent e) {
        Entity en = e.getEntity();
        if (en instanceof Player || en instanceof FallingBlock || en instanceof Sheep) return;
        if (settingOff(e.getBlock().getLocation(), Flag.MOB_GRIEF)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMobTrample(EntityInteractEvent e) {
        if (e.getEntity() instanceof Player) return;
        if (BlockRules.forPhysical(e.getBlock().getType()) == Flag.TRAMPLE
                && settingOff(e.getBlock().getLocation(), Flag.MOB_GRIEF)) e.setCancelled(true);
    }
}
