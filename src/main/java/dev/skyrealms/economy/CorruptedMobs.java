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
import dev.skyrealms.util.Msg;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Corrupted mob bounties: hostile mobs that spawn naturally on corrupted land can come out
 * corrupted, a little tougher, and drop Corrupted Remnants (and sometimes Void Shards) when a
 * player kills them.
 */
public final class CorruptedMobs implements Listener {
    private final SkyRealmsPlugin plugin;
    private final NamespacedKey key, healthKey, damageKey, speedKey;
    private final boolean enabled, particles;
    private final double baseChance, maxChance, bounty;
    private final double[] health = new double[4], damage = new double[4];
    private final double speed3;
    private final int[] remnantsMin = new int[4], remnantsMax = new int[4];
    private final double[] shardChance = new double[4];
    private final int[] shardsMax = new int[4];
    /** Corrupted mobs we know are loaded, for the particle task. */
    private final Set<UUID> tracked = new HashSet<>();

    public CorruptedMobs(SkyRealmsPlugin plugin, ConfigurationSection cfg) {
        this.plugin = plugin;
        if (cfg == null) cfg = new MemoryConfiguration();
        key = new NamespacedKey(plugin, "corrupted_stage");
        healthKey = new NamespacedKey(plugin, "corrupted_health");
        damageKey = new NamespacedKey(plugin, "corrupted_damage");
        speedKey = new NamespacedKey(plugin, "corrupted_speed");
        enabled = cfg.getBoolean("enabled", true);
        particles = cfg.getBoolean("particles", true);
        baseChance = clamp(cfg.getDouble("chance-at-stage-1", 0.15));
        maxChance = clamp(cfg.getDouble("chance-at-100", 0.6));
        bounty = Math.max(0, cfg.getDouble("coin-bounty", 0));
        speed3 = Math.max(0, cfg.getDouble("speed-bonus-stage-3", 0.15));
        for (int s = 1; s <= 3; s++) {
            String p = "stage-" + s + ".";
            health[s] = Math.max(0, cfg.getDouble(p + "health-bonus", 0.25 * s));
            damage[s] = Math.max(0, cfg.getDouble(p + "damage-bonus", 0.10 * s));
            remnantsMin[s] = Math.max(0, cfg.getInt(p + "remnants-min", s));
            remnantsMax[s] = Math.max(remnantsMin[s], cfg.getInt(p + "remnants-max", s + 1));
            shardChance[s] = clamp(cfg.getDouble(p + "shard-chance", 0.15 * s));
            shardsMax[s] = Math.max(1, cfg.getInt(p + "shards-max", s));
        }
    }

    public void start() {
        if (!enabled) return;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (particles) Bukkit.getScheduler().runTaskTimer(plugin, this::particleTick, 40L, 20L);
        // Pick up corrupted mobs that were already loaded (e.g. after a reload).
        World w = Bukkit.getWorld(plugin.decay().worldName());
        if (w != null) for (LivingEntity e : w.getLivingEntities()) if (stageOf(e) > 0) tracked.add(e.getUniqueId());
    }

    private static double clamp(double v) { return Math.max(0, Math.min(1, v)); }

    private int stageAt(Location l) {
        DecaySettings s = plugin.decay().settings();
        double v = plugin.decay().valueAt(l);
        return v >= s.stage3 ? 3 : v >= s.stage2 ? 2 : v >= s.stage1 ? 1 : 0;
    }

    /** 0 for a normal mob, otherwise the corruption stage it spawned at. */
    public int stageOf(Entity e) {
        Integer s = e.getPersistentDataContainer().get(key, PersistentDataType.INTEGER);
        return s == null ? 0 : s;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onSpawn(CreatureSpawnEvent e) {
        if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) return; // no spawners, eggs or commands
        if (!(e.getEntity() instanceof Monster mob)) return;
        var d = plugin.decay();
        if (d == null || !d.isRunning() || !mob.getWorld().getName().equals(d.worldName())) return;
        double v = d.valueAt(mob.getLocation());
        DecaySettings s = d.settings();
        if (v < s.stage1) return;
        double t = (v - s.stage1) / Math.max(1, 100 - s.stage1);
        double chance = baseChance + (maxChance - baseChance) * t;
        if (ThreadLocalRandom.current().nextDouble() >= chance) return;
        corrupt(mob, stageAt(mob.getLocation()));
    }

    /** Turns a mob into a corrupted one. Also used by admins and tests. */
    public void corrupt(LivingEntity mob, int stage) {
        stage = Math.max(1, Math.min(3, stage));
        mob.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, stage);
        boost(mob, Attribute.MAX_HEALTH, healthKey, health[stage]);
        boost(mob, Attribute.ATTACK_DAMAGE, damageKey, damage[stage]);
        if (stage == 3) boost(mob, Attribute.MOVEMENT_SPEED, speedKey, speed3);
        AttributeInstance max = mob.getAttribute(Attribute.MAX_HEALTH);
        if (max != null) mob.setHealth(max.getValue());
        String name = mob.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        mob.customName(Msg.c("<dark_purple>Corrupted " + name));
        mob.setCustomNameVisible(false); // shows when looked at, not through walls
        mob.setRemoveWhenFarAway(true);
        tracked.add(mob.getUniqueId());
    }

    private static void boost(LivingEntity mob, Attribute attr, NamespacedKey id, double amount) {
        if (amount <= 0) return;
        AttributeInstance a = mob.getAttribute(attr);
        if (a == null) return;
        a.removeModifier(id);
        a.addModifier(new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_SCALAR, EquipmentSlotGroup.ANY));
    }

    @EventHandler(ignoreCancelled = true)
    public void onDeath(EntityDeathEvent e) {
        LivingEntity mob = e.getEntity();
        int stage = stageOf(mob);
        if (stage == 0) return;
        tracked.remove(mob.getUniqueId());
        Player killer = mob.getKiller();
        if (killer == null || killer.getGameMode() == GameMode.CREATIVE || killer.getGameMode() == GameMode.SPECTATOR) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int remnants = remnantsMin[stage] + r.nextInt(remnantsMax[stage] - remnantsMin[stage] + 1);
        if (remnants > 0) e.getDrops().add(VoidItems.create(VoidItems.Kind.REMNANT, remnants));
        if (r.nextDouble() < shardChance[stage]) e.getDrops().add(VoidItems.create(VoidItems.Kind.SHARD, 1 + r.nextInt(shardsMax[stage])));
        if (bounty > 0) {
            double pay = bounty * stage;
            plugin.money().refund(killer, pay);
            killer.sendActionBar(Msg.c("<dark_purple>Bounty <gold>+" + plugin.money().format(pay)));
        }
        mob.getWorld().spawnParticle(Particle.REVERSE_PORTAL, mob.getLocation().add(0, 1, 0), 30, 0.4, 0.6, 0.4, 0.05);
    }

    @EventHandler
    public void onRemove(EntityRemoveEvent e) {
        tracked.remove(e.getEntity().getUniqueId());
    }

    private void particleTick() {
        if (tracked.isEmpty()) return;
        Iterator<UUID> it = tracked.iterator();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        while (it.hasNext()) {
            Entity e = Bukkit.getEntity(it.next());
            if (e == null || !e.isValid() || e.isDead()) { it.remove(); continue; }
            if (r.nextInt(3) != 0) continue; // occasional, not constant
            Location l = e.getLocation().add(0, 1, 0);
            e.getWorld().spawnParticle(Particle.ASH, l, 6, 0.3, 0.5, 0.3, 0);
            if (stageOf(e) >= 2) e.getWorld().spawnParticle(Particle.PORTAL, l, 4, 0.3, 0.5, 0.3, 0.1);
        }
    }
}
