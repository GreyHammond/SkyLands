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
import dev.skyrealms.economy.VoidItems.Kind;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.SmithingInventory;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Warded armor is made, not bought:
 * <pre>
 *   Ward Template      = 8 Void Shards around Crying Obsidian            (crafting table)
 *   Corrupted Template = Ward Template surrounded by 8 Corrupted Remnants (crafting table)
 *   Ward I   = Ward Template      + unwarded armor + Void Shard          (smithing table)
 *   Ward II  = Corrupted Template + Ward I armor   + Corrupted Remnant   (smithing table)
 *   Ward III = Corrupted Template + Ward II armor  + a real Echo Shard   (smithing table)
 * </pre>
 * The smithing recipes only exist so the table accepts the inputs; the real rules (which template,
 * which current level, which material) are enforced in {@link #onPrepare}, which also builds the
 * result from the player's own armor so enchantments, trims, damage and name carry over.
 */
public final class WardSmithing implements Listener {
    private final Plugin plugin;
    private final WardedGear ward;
    private final List<NamespacedKey> keys = new ArrayList<>();

    public WardSmithing(Plugin plugin, WardedGear ward) {
        this.plugin = plugin;
        this.ward = ward;
    }

    public void start() {
        RecipeChoice shard = new RecipeChoice.ExactChoice(VoidItems.create(Kind.SHARD, 1));
        RecipeChoice remnant = new RecipeChoice.ExactChoice(VoidItems.create(Kind.REMNANT, 1));
        RecipeChoice wardTemplate = new RecipeChoice.ExactChoice(VoidItems.create(Kind.WARD_TEMPLATE, 1));
        RecipeChoice corruptedTemplate = new RecipeChoice.ExactChoice(VoidItems.create(Kind.CORRUPTED_TEMPLATE, 1));
        RecipeChoice armor = new RecipeChoice.MaterialChoice(armorMaterials());

        ShapedRecipe template = new ShapedRecipe(key("ward_template"), VoidItems.create(Kind.WARD_TEMPLATE, 1));
        template.shape("SSS", "SOS", "SSS");
        template.setIngredient('S', shard);
        template.setIngredient('O', Material.CRYING_OBSIDIAN);
        add(template);

        ShapedRecipe corrupted = new ShapedRecipe(key("corrupted_template"), VoidItems.create(Kind.CORRUPTED_TEMPLATE, 1));
        corrupted.shape("RRR", "RTR", "RRR");
        corrupted.setIngredient('R', remnant);
        corrupted.setIngredient('T', wardTemplate);
        add(corrupted);

        // Results are placeholders; onPrepare replaces them with the player's armor at the new level.
        ItemStack placeholder = new ItemStack(Material.IRON_CHESTPLATE);
        add(new SmithingTransformRecipe(key("ward_1"), placeholder, wardTemplate, armor, shard, false));
        add(new SmithingTransformRecipe(key("ward_2"), placeholder, corruptedTemplate, armor, remnant, false));
        add(new SmithingTransformRecipe(key("ward_3"), placeholder, corruptedTemplate, armor,
                new RecipeChoice.MaterialChoice(Material.ECHO_SHARD), false));

        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player p : Bukkit.getOnlinePlayers()) p.discoverRecipes(keys);
    }

    private NamespacedKey key(String id) { return new NamespacedKey(plugin, id); }

    private void add(org.bukkit.inventory.Recipe recipe) {
        NamespacedKey k = ((org.bukkit.Keyed) recipe).getKey();
        Bukkit.removeRecipe(k); // after a reload the old copy is still registered
        Bukkit.addRecipe(recipe);
        keys.add(k);
    }

    private static List<Material> armorMaterials() {
        List<Material> out = new ArrayList<>();
        for (Material m : Material.values()) {
            if (!m.isLegacy() && m.isItem() && WardedGear.armorSlot(m) != null) out.add(m);
        }
        return out;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { e.getPlayer().discoverRecipes(keys); }

    /**
     * Decides the smithing result. Runs whenever the table's inputs change, so the item a player
     * takes is always the one computed here; the table then uses up one of each input as usual.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepare(PrepareSmithingEvent e) {
        SmithingInventory inv = e.getInventory();
        ItemStack template = inv.getInputTemplate();
        ItemStack base = inv.getInputEquipment();
        ItemStack addition = inv.getInputMineral();
        Kind tk = VoidItems.kindOf(template);
        Kind ak = VoidItems.kindOf(addition);

        if (tk == null) {
            // A vanilla template: leave vanilla smithing alone, except that void items are never trim materials.
            if (ak != null || VoidItems.kindOf(base) != null) e.setResult(null);
            return;
        }
        // Our template: never a vanilla trim. Only the exact ward steps produce anything.
        e.setResult(wardResult(tk, base, addition, ak));
    }

    /** The armor this combination produces, or null if it isn't a valid ward step. */
    private ItemStack wardResult(Kind template, ItemStack base, ItemStack addition, Kind additionKind) {
        if (base == null || base.getType().isAir() || WardedGear.armorSlot(base.getType()) == null) return null;
        if (addition == null || addition.getType().isAir()) return null;
        int current = ward.level(base);
        int next;
        if (template == Kind.WARD_TEMPLATE && current == 0 && additionKind == Kind.SHARD) next = 1;
        else if (template == Kind.CORRUPTED_TEMPLATE && current == 1 && additionKind == Kind.REMNANT) next = 2;
        else if (template == Kind.CORRUPTED_TEMPLATE && current == 2 && additionKind == null
                && addition.getType() == Material.ECHO_SHARD) next = 3;
        else return null;
        ItemStack out = base.clone();
        out.setAmount(1);
        ward.setLevel(out, next);
        return out;
    }
}
