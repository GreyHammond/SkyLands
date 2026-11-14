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
package dev.skyrealms.player;

import dev.skyrealms.SkyRealmsPlugin;
import dev.skyrealms.economy.VoidItems;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Collects milestones from every feature and passes them to whoever listens (the tutorial).
 * Also reports the milestones that come straight from vanilla events: picking items up,
 * crafting, smithing and taking a tonic out of a brewing stand.
 */
public final class Milestones implements Listener {
    @FunctionalInterface
    public interface Handler { void on(Player player, Milestone milestone, int amount); }

    private final SkyRealmsPlugin plugin;
    private final List<Handler> handlers = new CopyOnWriteArrayList<>();

    public Milestones(SkyRealmsPlugin plugin) { this.plugin = plugin; }

    public void listen(Handler h) { handlers.add(h); }

    public void trigger(Player player, Milestone m) { trigger(player, m, 1); }

    public void trigger(Player player, Milestone m, int amount) {
        if (player == null || !player.isOnline()) return;
        for (Handler h : handlers) {
            try { h.on(player, m, amount); }
            catch (RuntimeException e) { plugin.getLogger().warning("Milestone handler failed on " + m + ": " + e); }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        ItemStack it = e.getItem().getItemStack();
        VoidItems.Kind k = VoidItems.kindOf(it);
        if (k == VoidItems.Kind.SHARD) trigger(p, Milestone.COLLECTED_SHARD, it.getAmount());
        else if (k == VoidItems.Kind.CORRUPTED_SPIDER_EYE) trigger(p, Milestone.COLLECTED_CORRUPTED_SPIDER_EYE, it.getAmount());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onCraft(CraftItemEvent e) {
        if (e.getWhoClicked() instanceof Player p && VoidItems.kindOf(e.getRecipe().getResult()) == VoidItems.Kind.WARD_TEMPLATE) {
            trigger(p, Milestone.CRAFTED_WARD_TEMPLATE);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onSmith(SmithItemEvent e) {
        var ward = plugin.ward();
        if (ward != null && e.getWhoClicked() instanceof Player p && ward.level(e.getInventory().getResult()) > 0) {
            trigger(p, Milestone.SMITHED_WARD);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBrewTake(InventoryClickEvent e) {
        if (e.getInventory().getType() != InventoryType.BREWING || e.getClickedInventory() != e.getInventory()) return;
        var tonic = plugin.tonic();
        if (tonic != null && e.getWhoClicked() instanceof Player p && tonic.levelOf(e.getCurrentItem()) == 1) {
            trigger(p, Milestone.BREWED_TONIC);
        }
    }
}
