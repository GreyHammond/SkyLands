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
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Purified Essence: corruption brought back from the world below and purified at the Void
 * Exchange. Islands grow on it. Every island price in config is written in money; with Essence on,
 * it's converted at {@code money-per-essence} and rounded up.
 */
public final class Essence {
    private final SkyRealmsPlugin plugin;
    public final boolean islands;
    private final double moneyPerEssence;
    private final int perShard, perRemnant;

    public Essence(SkyRealmsPlugin plugin, ConfigurationSection cfg) {
        this.plugin = plugin;
        if (cfg == null) cfg = new MemoryConfiguration();
        islands = cfg.getBoolean("islands", true);
        moneyPerEssence = Math.max(1, cfg.getDouble("money-per-essence", 125));
        perShard = Math.max(0, cfg.getInt("purify.shard", 1));
        perRemnant = Math.max(0, cfg.getInt("purify.remnant", 4));
        rewardRate = Math.max(1, cfg.getDouble("money-per-reward-essence", 25));
    }

    public long balance(UUID id) { return plugin.profiles().get(id).essence; }

    public void add(UUID id, long amount) {
        var p = plugin.profiles().get(id);
        p.essence = Math.max(0, p.essence + amount);
        plugin.profiles().dirty();
    }

    /** A money price from config, as Essence (rounded up; 0 stays 0). */
    public long fromMoney(double money) { return money <= 0 ? 0 : (long) Math.ceil(money / moneyPerEssence); }

    public static String format(long amount) { return String.format("%,d Essence", amount); }

    /** Takes the Essence equal to a money price. False (nothing taken) if the player has too little. */
    public boolean charge(Player p, double moneyPrice) {
        long cost = fromMoney(moneyPrice);
        if (cost <= 0) return true;
        if (balance(p.getUniqueId()) < cost) return false;
        add(p.getUniqueId(), -cost);
        return true;
    }

    public void refund(Player p, double moneyPrice) { long n = fromMoney(moneyPrice); if (n > 0) add(p.getUniqueId(), n); }

    /** Rewards (tutorial, contracts, tips, bounties) are written in money; with Essence on they pay Essence at this rate. */
    private double rewardRate = 25;

    public void rewardRate(double r) { rewardRate = Math.max(1, r); }

    private long rewardEssence(double money) { return money <= 0 ? 0 : Math.max(1, Math.round(money / rewardRate)); }

    /** Pays a reward: Essence when islands run on Essence, otherwise money. Works for offline players. */
    public void payReward(org.bukkit.OfflinePlayer p, double money) {
        if (money <= 0) return;
        if (islands) add(p.getUniqueId(), rewardEssence(money)); else plugin.money().refund(p, money);
    }

    /** How a reward reads: "8 Essence", or money when Essence is off. */
    public String rewardLabel(double money) { return islands ? format(rewardEssence(money)) : plugin.money().format(money); }

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
