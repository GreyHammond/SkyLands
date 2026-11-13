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
package dev.skyrealms.menu;

import dev.skyrealms.SkyRealmsPlugin;
import dev.skyrealms.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Hotbar shortcuts: an enchanted book that opens the Skyland menu and an enchanted feather that
 * flies you home, locked into the hotbar so players (Bedrock especially) never need a command.
 * They can't be dropped, thrown, moved, stored or lost on death; each player can turn them off.
 */
public final class HotbarShortcuts implements Listener {
    public enum Kind { MENU, HOME }

    private final SkyRealmsPlugin plugin;
    private final NamespacedKey itemKey, prefKey;
    private final boolean enabled;
    private final String defaultFor;
    private final int menuSlot, homeSlot;
    private final Map<UUID, Long> lastUse = new HashMap<>();

    public HotbarShortcuts(SkyRealmsPlugin plugin, ConfigurationSection cfg) {
        this.plugin = plugin;
        this.itemKey = new NamespacedKey(plugin, "shortcut");
        this.prefKey = new NamespacedKey(plugin, "shortcuts");
        this.enabled = cfg == null || cfg.getBoolean("enabled", true);
        this.defaultFor = cfg == null ? "bedrock" : cfg.getString("default", "bedrock").toLowerCase(java.util.Locale.ROOT);
        int m = cfg == null ? 9 : cfg.getInt("menu-slot", 9), h = cfg == null ? 8 : cfg.getInt("home-slot", 8);
        this.menuSlot = Math.max(1, Math.min(9, m)) - 1;
        int hs = Math.max(1, Math.min(9, h)) - 1;
        this.homeSlot = hs == menuSlot ? (menuSlot == 8 ? 7 : menuSlot + 1) : hs;
        Bukkit.getScheduler().runTaskTimer(plugin, () -> Bukkit.getOnlinePlayers().forEach(this::ensure), 100L, 40L);
    }

    public boolean available() { return enabled; }

    /** Whether this player has the shortcuts: their own choice, or the server default. */
    public boolean isOn(Player p) {
        if (!enabled) return false;
        Byte b = p.getPersistentDataContainer().get(prefKey, PersistentDataType.BYTE);
        if (b != null) return b == 1;
        return switch (defaultFor) {
            case "all" -> true;
            case "none" -> false;
            default -> plugin.bedrock().handles(p);
        };
    }

    public void set(Player p, boolean on) {
        p.getPersistentDataContainer().set(prefKey, PersistentDataType.BYTE, (byte) (on ? 1 : 0));
        ensure(p);
        Msg.send(p, on ? "Hotbar shortcuts on: the <aqua>menu book</aqua> is in slot " + (menuSlot + 1)
                + " and the <aqua>return feather</aqua> in slot " + (homeSlot + 1) + "."
                : "Hotbar shortcuts off. Turn them back on from the <white>/skyland</white> menu.");
    }

    public void toggle(Player p) { set(p, !isOn(p)); }

    // ---- items ----------------------------------------------------------------------------

    public Kind kind(ItemStack it) {
        if (it == null || it.isEmpty() || !it.hasItemMeta()) return null;
        String s = it.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
        if (s == null) return null;
        try { return Kind.valueOf(s); } catch (IllegalArgumentException e) { return null; }
    }

    private ItemStack make(Kind k) {
        ItemStack it = new ItemStack(k == Kind.MENU ? Material.BOOK : Material.FEATHER);
        it.editMeta(m -> {
            if (k == Kind.MENU) {
                m.displayName(Msg.c("<!italic><aqua>Skyland Menu"));
                m.lore(List.of(Msg.c("<!italic><gray>Use to open your island menu."), Msg.c("<!italic><dark_gray>Turn off in the Skyland menu.")));
            } else {
                m.displayName(Msg.c("<!italic><green>Return to Island"));
                m.lore(List.of(Msg.c("<!italic><gray>Use to ride the wind home."), Msg.c("<!italic><dark_gray>Turn off in the Skyland menu.")));
            }
            m.setEnchantmentGlintOverride(true);
            m.setMaxStackSize(1);
            m.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, k.name());
        });
        return it;
    }

    private int slot(Kind k) { return k == Kind.MENU ? menuSlot : homeSlot; }

    /** Puts the shortcuts in their slots (or removes them), fixing duplicates and strays. */
    public void ensure(Player p) {
        if (!p.isOnline() || p.isDead() || p.getGameMode() == GameMode.SPECTATOR) return;
        PlayerInventory inv = p.getInventory();
        boolean on = isOn(p);
        if (kind(p.getItemOnCursor()) != null) p.setItemOnCursor(null);
        for (Kind k : Kind.values()) {
            int want = slot(k);
            boolean placed = false;
            for (int s = 0; s < inv.getSize(); s++) {
                if (kind(inv.getItem(s)) != k) continue;
                if (on && s == want && !placed) placed = true;
                else inv.setItem(s, null);
            }
            if (!on || placed) continue;
            ItemStack there = inv.getItem(want);
            if (there != null && !there.isEmpty()) {
                int free = freeSlot(inv);
                if (free < 0) continue;                   // inventory full: try again in a moment
                inv.setItem(free, there);
            }
            inv.setItem(want, make(k));
        }
    }

    private int freeSlot(PlayerInventory inv) {
        for (int s = 9; s < 36; s++) if (isEmpty(inv.getItem(s))) return s;
        for (int s = 0; s < 9; s++) if (s != menuSlot && s != homeSlot && isEmpty(inv.getItem(s))) return s;
        return -1;
    }

    private static boolean isEmpty(ItemStack it) { return it == null || it.isEmpty(); }

    private void use(Player p, Kind k) {
        long now = System.currentTimeMillis();
        Long last = lastUse.put(p.getUniqueId(), now);
        if (last != null && now - last < 600) return;     // Bedrock repeats "use" while held
        if (k == Kind.MENU) plugin.menus().main(p);
        else plugin.service().launchHome(p);
    }

    // ---- keep them in place ---------------------------------------------------------------

    @EventHandler public void onJoin(PlayerJoinEvent e) { later(e.getPlayer(), 20L); }
    @EventHandler public void onRespawn(PlayerRespawnEvent e) { later(e.getPlayer(), 1L); }
    @EventHandler public void onWorld(PlayerChangedWorldEvent e) { later(e.getPlayer(), 1L); }
    @EventHandler public void onMode(PlayerGameModeChangeEvent e) { later(e.getPlayer(), 1L); }
    @EventHandler public void onQuit(PlayerQuitEvent e) { lastUse.remove(e.getPlayer().getUniqueId()); }

    private void later(Player p, long ticks) { Bukkit.getScheduler().runTaskLater(plugin, () -> ensure(p), ticks); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent e) {
        if (kind(e.getItemDrop().getItemStack()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        e.getDrops().removeIf(it -> kind(it) != null);
        e.getItemsToKeep().removeIf(it -> kind(it) != null);
    }

    /** Whatever happens, a shortcut never exists as an item in the world. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpawn(ItemSpawnEvent e) {
        if (kind(e.getEntity().getItemStack()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ItemStack hot = e.getClick() == ClickType.NUMBER_KEY && e.getHotbarButton() >= 0 ? p.getInventory().getItem(e.getHotbarButton()) : null;
        ItemStack off = e.getClick() == ClickType.SWAP_OFFHAND ? p.getInventory().getItemInOffHand() : null;
        Kind k = kind(e.getCurrentItem());
        if (k == null && kind(e.getCursor()) == null && kind(hot) == null && kind(off) == null) return;
        e.setCancelled(true);
        // Tapping a shortcut in your own inventory uses it (handy on Bedrock).
        if (k != null && (e.getClick() == ClickType.LEFT || e.getClick() == ClickType.RIGHT)
                && e.getView().getTopInventory().getType() == InventoryType.CRAFTING) {
            Bukkit.getScheduler().runTask(plugin, () -> { p.closeInventory(); use(p, k); });
        }
        Bukkit.getScheduler().runTask(plugin, () -> ensure(p));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent e) {
        if (kind(e.getOldCursor()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (kind(e.getMainHandItem()) != null || kind(e.getOffHandItem()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Kind k = kind(e.getItem());
        if (k == null) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        e.setCancelled(true);
        use(e.getPlayer(), k);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntity(PlayerInteractEntityEvent e) {
        if (kind(e.getPlayer().getInventory().getItem(e.getHand())) != null) e.setCancelled(true);  // no item frames
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onStand(PlayerArmorStandManipulateEvent e) {
        if (kind(e.getPlayerItem()) != null) e.setCancelled(true);
    }
}
