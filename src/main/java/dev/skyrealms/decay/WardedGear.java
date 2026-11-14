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
package dev.skyrealms.decay;

import dev.skyrealms.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Warded gear: helmet, chestplate, leggings and boots carry a Warded level (1-3) in their
 * persistent data. Each worn piece cuts the decay its wearer causes; the total is capped.
 */
public final class WardedGear implements DecayResist {
    public static final int MAX_LEVEL = 3;
    private static final String LORE_PREFIX = "Warded ";
    private static final String[] ROMAN = {"", "I", "II", "III"};

    private final NamespacedKey key;
    private final double[] perPiece = new double[MAX_LEVEL + 1];
    private final double maxReduction;
    private final double[] prices = new double[MAX_LEVEL + 1];
    private final int[] requiredLevels = new int[MAX_LEVEL + 1];

    public WardedGear(Plugin plugin, ConfigurationSection section) {
        ConfigurationSection c = section != null ? section : new MemoryConfiguration();
        this.key = new NamespacedKey(plugin, "warded");
        double[] defReduce = {0, 0.05, 0.10, 0.20};
        double[] defPrice = {0, 2500, 7500, 20000};
        int[] defLevel = {0, 5, 15, 30};
        for (int lv = 1; lv <= MAX_LEVEL; lv++) {
            perPiece[lv] = clamp(c.getDouble("reduction." + lv, defReduce[lv]), 0, 1);
            prices[lv] = Math.max(0, c.getDouble("price." + lv, defPrice[lv]));
            requiredLevels[lv] = Math.max(0, c.getInt("required-island-level." + lv, defLevel[lv]));
        }
        this.maxReduction = clamp(c.getDouble("max-reduction", 0.80), 0, 1);
    }

    public double price(int level) { return prices[level]; }
    public int requiredIslandLevel(int level) { return requiredLevels[level]; }

    // ---- items -------------------------------------------------------------------------

    /** The armor slot this material belongs in, or null if it isn't head/chest/legs/feet armor. */
    public static EquipmentSlot armorSlot(Material m) {
        if (m == null || m.isAir()) return null;
        String n = m.name();
        if (n.endsWith("_HELMET") || m == Material.TURTLE_HELMET) return EquipmentSlot.HEAD;
        if (n.endsWith("_CHESTPLATE")) return EquipmentSlot.CHEST;
        if (n.endsWith("_LEGGINGS")) return EquipmentSlot.LEGS;
        if (n.endsWith("_BOOTS")) return EquipmentSlot.FEET;
        return null;
    }

    public int level(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 0;
        Integer lv = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.INTEGER);
        return lv == null ? 0 : Math.max(0, Math.min(MAX_LEVEL, lv));
    }

    /** Sets the Warded level (0 removes it) and keeps the lore line in sync. */
    public void setLevel(ItemStack item, int level) {
        int lv = Math.max(0, Math.min(MAX_LEVEL, level));
        item.editMeta(meta -> {
            if (lv == 0) meta.getPersistentDataContainer().remove(key);
            else meta.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, lv);
            syncLore(meta, lv);
        });
    }

    private static void syncLore(ItemMeta meta, int lv) {
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.removeIf(line -> PlainTextComponentSerializer.plainText().serialize(line).startsWith(LORE_PREFIX));
        if (lv > 0) lore.add(0, Msg.c("<!italic><dark_aqua>" + LORE_PREFIX + ROMAN[lv]));
        meta.lore(lore.isEmpty() ? null : lore);
    }

    public static String roman(int level) { return ROMAN[Math.max(0, Math.min(MAX_LEVEL, level))]; }

    // ---- resist ------------------------------------------------------------------------

    /** Total share of decay removed by the armor the player is wearing (0..max-reduction). */
    public double reduction(Player p) {
        var inv = p.getInventory();
        ItemStack[] worn = {inv.getHelmet(), inv.getChestplate(), inv.getLeggings(), inv.getBoots()};
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        double total = 0;
        for (int i = 0; i < worn.length; i++) {
            ItemStack it = worn[i];
            if (it == null || armorSlot(it.getType()) != slots[i]) continue;
            total += perPiece[level(it)];
        }
        return Math.min(maxReduction, total);
    }

    @Override
    public double factor(Player player) { return 1.0 - reduction(player); }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
}
