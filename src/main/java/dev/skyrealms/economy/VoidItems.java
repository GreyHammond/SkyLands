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

import dev.skyrealms.util.Msg;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Items that come out of the corruption. They're tagged in persistent data, so renamed vanilla
 * items can never pass for them. Sold at the Void Exchange.
 */
public final class VoidItems {
    public enum Kind {
        /** Dropped by corrupted land and corrupted mobs. */
        SHARD(Material.ECHO_SHARD, "<dark_purple>Void Shard", "<gray>A splinter of the corruption.", "<gray>Sell it at the <light_purple>Void Exchange</light_purple>."),
        /** Dropped by corrupted mobs. */
        REMNANT(Material.PHANTOM_MEMBRANE, "<red>Corrupted Remnant", "<gray>Torn from a corrupted creature.", "<gray>Sell it at the <light_purple>Void Exchange</light_purple>.");

        public final Material material;
        final String name;
        final String[] lore;

        Kind(Material material, String name, String... lore) { this.material = material; this.name = name; this.lore = lore; }

        public String id() { return name().toLowerCase(Locale.ROOT); }

        public static Kind byId(String id) {
            for (Kind k : values()) if (k.id().equalsIgnoreCase(id)) return k;
            return null;
        }
    }

    private static NamespacedKey key;

    private VoidItems() {}

    public static void init(Plugin plugin) { key = new NamespacedKey(plugin, "void_item"); }

    public static ItemStack create(Kind kind, int amount) {
        ItemStack it = new ItemStack(kind.material, Math.max(1, Math.min(kind.material.getMaxStackSize(), amount)));
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Msg.c("<!italic>" + kind.name));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        for (String l : kind.lore) lore.add(Msg.c("<!italic>" + l));
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, kind.id());
        it.setItemMeta(meta);
        return it;
    }

    /** The kind of void item this is, or null for anything else. */
    public static Kind kindOf(ItemStack it) {
        if (it == null || key == null || !it.hasItemMeta()) return null;
        String id = it.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        return id == null ? null : Kind.byId(id);
    }
}
