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

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;

/** Stops void items being used as the vanilla items they look like (crafting, anvils). */
public final class VoidExchange implements Listener {
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
