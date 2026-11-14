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
        /** Dropped by corrupted land and corrupted mobs. (Amethyst-looking, so it's never confused with a real echo shard.) */
        SHARD(true, Material.AMETHYST_SHARD, "<dark_purple>Void Shard", "<gray>A splinter of the corruption.", "<gray>Sell it at the <light_purple>Void Exchange</light_purple>."),
        /** Dropped by corrupted mobs. */
        REMNANT(true, Material.PHANTOM_MEMBRANE, "<red>Corrupted Remnant", "<gray>Torn from a corrupted creature.", "<gray>Sell it at the <light_purple>Void Exchange</light_purple>."),
        /** Crafted: 8 Void Shards around Crying Obsidian. Smiths Ward I. */
        WARD_TEMPLATE(false, Material.WARD_ARMOR_TRIM_SMITHING_TEMPLATE, "<light_purple>Ward Template", "<gray>Smith onto armor with a Void Shard", "<gray>to ward it against the corruption."),
        /** Crafted: a Ward Template surrounded by Corrupted Remnants. Smiths Ward II and III. */
        CORRUPTED_TEMPLATE(false, Material.SILENCE_ARMOR_TRIM_SMITHING_TEMPLATE, "<dark_purple>Corrupted Template", "<gray>Smith onto Ward I armor with a Remnant,", "<gray>or onto Ward II armor with an Echo Shard."),
        /** Corrupted spiders. Brews Cleansing Tonic I. */
        CORRUPTED_SPIDER_EYE(false, Material.SPIDER_EYE, "<dark_purple>Corrupted Spider Eye", "<gray>Still twitching with corruption.", "<gray>A brewing ingredient."),
        /** Corrupted skeletons. Brews Cleansing Tonic II. */
        CORRUPTED_BONE(false, Material.BONE, "<dark_purple>Corrupted Bone", "<gray>Blackened to the marrow.", "<gray>A brewing ingredient."),
        /** Corrupted creepers. Turns a tonic into a splash tonic. */
        CORRUPTED_GUNPOWDER(false, Material.GUNPOWDER, "<dark_purple>Corrupted Gunpowder", "<gray>It hums faintly.", "<gray>A brewing ingredient."),
        /** Corrupted zombies. */
        CORRUPTED_FLESH(false, Material.ROTTEN_FLESH, "<dark_purple>Corrupted Flesh", "<gray>Even flies avoid it.", "<gray>A brewing ingredient.");

        /** Whether the Void Exchange buys it. */
        public final boolean sellable;
        public final Material material;
        final String name;
        final String[] lore;

        Kind(boolean sellable, Material material, String name, String... lore) { this.sellable = sellable; this.material = material; this.name = name; this.lore = lore; }

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
