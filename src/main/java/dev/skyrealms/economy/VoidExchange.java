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
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;

import java.util.EnumMap;
import java.util.Map;

/** Buys void items for money. Also stops them being used as their vanilla items. */
public final class VoidExchange implements Listener {
    private final SkyRealmsPlugin plugin;
    private final Map<VoidItems.Kind, Double> prices = new EnumMap<>(VoidItems.Kind.class);

    public VoidExchange(SkyRealmsPlugin plugin, ConfigurationSection cfg) {
        this.plugin = plugin;
        if (cfg == null) cfg = new MemoryConfiguration();
        prices.put(VoidItems.Kind.SHARD, Math.max(0, cfg.getDouble("prices.shard", 25)));
        prices.put(VoidItems.Kind.REMNANT, Math.max(0, cfg.getDouble("prices.remnant", 75)));
    }

    public double price(VoidItems.Kind kind) { return prices.getOrDefault(kind, 0.0); }

    /** How many of this kind (or all kinds, if null) the player carries. */
    public int count(Player p, VoidItems.Kind kind) {
        int n = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) {
            VoidItems.Kind k = VoidItems.kindOf(it);
            if (k != null && k.sellable && (kind == null || k == kind)) n += it.getAmount();
        }
        return n;
    }

    public double value(Player p, VoidItems.Kind kind) {
        double v = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) {
            VoidItems.Kind k = VoidItems.kindOf(it);
            if (k != null && k.sellable && (kind == null || k == kind)) v += price(k) * it.getAmount();
        }
        return v;
    }

    /** Sells every item of this kind (or every void item, if null). Returns what was paid. */
    public double sell(Player p, VoidItems.Kind kind) {
        ItemStack[] contents = p.getInventory().getStorageContents();
        double total = 0;
        for (int i = 0; i < contents.length; i++) {
            VoidItems.Kind k = VoidItems.kindOf(contents[i]);
            if (k == null || !k.sellable || (kind != null && k != kind)) continue;
            total += price(k) * contents[i].getAmount();
            contents[i] = null;
        }
        if (total <= 0) return 0;
        p.getInventory().setStorageContents(contents);
        plugin.money().refund(p, total);
        plugin.milestones().trigger(p, dev.skyrealms.player.Milestone.SOLD_AT_EXCHANGE);
        return total;
    }

    @EventHandler
    public void onCraft(PrepareItemCraftEvent e) {
        // Our own recipes (the result is a void item, e.g. a Ward Template) may use void items.
        if (e.getRecipe() != null && VoidItems.kindOf(e.getRecipe().getResult()) != null) return;
        for (ItemStack it : e.getInventory().getMatrix()) {
            if (VoidItems.kindOf(it) != null) { e.getInventory().setResult(null); return; }
        }
    }

    @EventHandler
    public void onAnvil(PrepareAnvilEvent e) {
        for (ItemStack it : e.getInventory().getContents()) {
            if (VoidItems.kindOf(it) != null) { e.setResult(null); return; }
        }
    }
}
