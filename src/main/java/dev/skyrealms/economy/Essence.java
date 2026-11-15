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
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Purified Essence, SkyLands' only currency: corruption brought back from the world below and
 * purified at the Purifier. Islands grow on it; rewards pay it. Stored per player in profiles.yml.
 */
public final class Essence {
    private final SkyRealmsPlugin plugin;
    private final int perShard, perRemnant;

    public Essence(SkyRealmsPlugin plugin, ConfigurationSection cfg) {
        this.plugin = plugin;
        if (cfg == null) cfg = new MemoryConfiguration();
        perShard = Math.max(0, cfg.getInt("purify.shard", 1));
        perRemnant = Math.max(0, cfg.getInt("purify.remnant", 4));
    }

    public long balance(UUID id) { return plugin.profiles().get(id).essence; }

    public void add(UUID id, long amount) {
        var p = plugin.profiles().get(id);
        p.essence = Math.max(0, p.essence + amount);
        plugin.profiles().dirty();
    }

    public static long round(double amount) { return amount <= 0 ? 0 : Math.round(Math.ceil(amount - 1e-9)); }

    public static String format(double amount) { return String.format("%,d Essence", round(amount)); }

    /** Takes this much Essence. False (nothing taken) if the player has too little. */
    public boolean charge(Player p, double amount) {
        long cost = round(amount);
        if (cost <= 0) return true;
        if (balance(p.getUniqueId()) < cost) return false;
        add(p.getUniqueId(), -cost);
        return true;
    }

    /** Gives Essence back or pays a reward. Works for offline players. */
    public void give(OfflinePlayer p, double amount) { long n = round(amount); if (n > 0) add(p.getUniqueId(), n); }

    public int perShard() { return perShard; }
    public int perRemnant() { return perRemnant; }

    /** Essence the player's shards and remnants would purify into. */
    public long purifyValue(Player p) {
        long v = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) v += worth(it);
        return v;
    }

    private long worth(ItemStack it) {
        VoidItems.Kind k = VoidItems.kindOf(it);
        if (k == VoidItems.Kind.SHARD) return (long) perShard * it.getAmount();
        if (k == VoidItems.Kind.REMNANT) return (long) perRemnant * it.getAmount();
        return 0;
    }

    /** Purifies every shard and remnant the player carries. Returns the Essence gained. */
    public long purify(Player p) {
        ItemStack[] c = p.getInventory().getStorageContents();
        long gained = 0;
        for (int i = 0; i < c.length; i++) {
            long w = worth(c[i]);
            if (w > 0) { gained += w; c[i] = null; }
        }
        if (gained <= 0) return 0;
        p.getInventory().setStorageContents(c);
        add(p.getUniqueId(), gained);
        plugin.milestones().trigger(p, dev.skyrealms.player.Milestone.SOLD_AT_EXCHANGE);
        return gained;
    }
}
