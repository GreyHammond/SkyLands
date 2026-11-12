package dev.skyrealms.menu;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public final class MenuListener implements Listener {
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof Menu menu)) return;
        e.setCancelled(true); // nothing can be taken out or put in, from either inventory
        if (e.getWhoClicked() instanceof Player p && e.getRawSlot() >= 0 && e.getRawSlot() < menu.size()) {
            menu.click(p, e.getRawSlot());
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder(false) instanceof Menu) e.setCancelled(true);
    }
}
