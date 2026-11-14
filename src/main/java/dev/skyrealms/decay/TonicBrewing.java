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

import dev.skyrealms.economy.VoidItems;
import dev.skyrealms.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.BrewingRecipe;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.List;

/**
 * Brewing recipes for the Cleansing Tonic. Every ingredient is a corrupted item from the
 * corruption (tagged void items), never the vanilla item it looks like.
 */
public final class TonicBrewing {
    /** Shown to players when they try to buy a tonic while the shop is off. */
    public static final List<String> RECIPE_STEPS = List.of(
            "Water Bottle + <dark_purple>Void Shard</dark_purple> -> <dark_purple>Tainted Potion</dark_purple>",
            "Tainted Potion + <dark_purple>Corrupted Spider Eye</dark_purple> -> <aqua>Cleansing Tonic I</aqua>",
            "Tonic I + <dark_purple>Corrupted Bone</dark_purple> -> <aqua>Cleansing Tonic II</aqua>",
            "Tonic II + <red>Corrupted Remnant</red> -> <aqua>Cleansing Tonic III</aqua>",
            "Any tonic + <dark_purple>Corrupted Gunpowder</dark_purple> -> <aqua>Splash</aqua> tonic");

    private final Plugin plugin;
    private final CleansingTonic tonic;
    private final NamespacedKey taintedKey;
    private final List<NamespacedKey> registered = new ArrayList<>();

    public TonicBrewing(Plugin plugin, CleansingTonic tonic) {
        this.plugin = plugin;
        this.tonic = tonic;
        this.taintedKey = new NamespacedKey(plugin, "tainted_potion");
    }

    /** The corrupted base for every tonic. No effect on its own. */
    public ItemStack tainted() {
        ItemStack item = new ItemStack(Material.POTION);
        item.editMeta(PotionMeta.class, m -> {
            m.setColor(Color.fromRGB(0x3B0F4F));
            m.displayName(Msg.c("<!italic><dark_purple>Tainted Potion"));
            m.lore(List.of(Msg.c("<!italic><gray>A base for corrupted brews."), Msg.c("<!italic><dark_gray>No effect on its own.")));
            m.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            m.getPersistentDataContainer().set(taintedKey, PersistentDataType.BYTE, (byte) 1);
        });
        return item;
    }

    public boolean isTainted(ItemStack it) {
        return it != null && it.getType() == Material.POTION && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(taintedKey, PersistentDataType.BYTE);
    }

    /** A plain water bottle (not one of our potions). */
    private static boolean isWater(ItemStack it) {
        if (it == null || it.getType() != Material.POTION || !(it.getItemMeta() instanceof PotionMeta m)) return false;
        return m.getBasePotionType() == PotionType.WATER && m.getPersistentDataContainer().isEmpty() && !m.hasCustomEffects();
    }

    private static RecipeChoice voidItem(VoidItems.Kind kind) {
        return RecipeChoice.predicateChoice(it -> VoidItems.kindOf(it) == kind, VoidItems.create(kind, 1));
    }

    private static RecipeChoice matching(java.util.function.Predicate<ItemStack> test, ItemStack example) {
        return RecipeChoice.predicateChoice(test, example);
    }

    public void register() {
        ItemStack water = new ItemStack(Material.POTION);
        water.editMeta(PotionMeta.class, m -> m.setBasePotionType(PotionType.WATER));
        mix("tainted_potion", tainted(), matching(TonicBrewing::isWater, water), voidItem(VoidItems.Kind.SHARD));
        mix("cleansing_tonic_1", tonic.create(1, 1), matching(this::isTainted, tainted()), voidItem(VoidItems.Kind.CORRUPTED_SPIDER_EYE));
        mix("cleansing_tonic_2", tonic.create(2, 1), matching(it -> tonic.levelOf(it) == 1, tonic.create(1, 1)), voidItem(VoidItems.Kind.CORRUPTED_BONE));
        mix("cleansing_tonic_3", tonic.create(3, 1), matching(it -> tonic.levelOf(it) == 2, tonic.create(2, 1)), voidItem(VoidItems.Kind.REMNANT));
        for (int lv = 1; lv <= CleansingTonic.MAX_LEVEL; lv++) {
            final int level = lv;
            mix("cleansing_tonic_splash_" + lv, tonic.createSplash(lv, 1),
                    matching(it -> tonic.levelOf(it) == level, tonic.create(lv, 1)), voidItem(VoidItems.Kind.CORRUPTED_GUNPOWDER));
        }
        plugin.getLogger().info("Registered " + registered.size() + " Cleansing Tonic brewing recipes.");
    }

    private void mix(String id, ItemStack result, RecipeChoice input, RecipeChoice ingredient) {
        NamespacedKey key = new NamespacedKey(plugin, id);
        Bukkit.removeRecipe(key); // left over from a reload
        if (Bukkit.addRecipe(new BrewingRecipe(key, result, input, ingredient))) registered.add(key);
        else plugin.getLogger().warning("Couldn't register brewing recipe " + id + ".");
    }
}
