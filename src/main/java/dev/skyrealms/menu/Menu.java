package dev.skyrealms.menu;

import dev.skyrealms.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.*;
import java.util.function.Consumer;

/**
 * A chest menu of clickable buttons. Every click is a plain single click, so it works the
 * same for Java players and Bedrock players coming through Geyser/Floodgate.
 */
public final class Menu implements InventoryHolder {
    private final Inventory inventory;
    private final Map<Integer, Consumer<Player>> actions = new HashMap<>();

    public Menu(int rows, String title) {
        this.inventory = Bukkit.createInventory(this, Math.max(1, Math.min(6, rows)) * 9, Msg.c(title));
    }

    public Menu button(int slot, ItemStack icon, Consumer<Player> onClick) {
        inventory.setItem(slot, icon);
        if (onClick != null) actions.put(slot, onClick);
        else actions.remove(slot);
        return this;
    }

    /** Fill empty slots with blank panes so Bedrock's chest UI looks tidy. */
    public Menu fill() {
        ItemStack pane = item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inventory.getSize(); i++) if (inventory.getItem(i) == null) inventory.setItem(i, pane);
        return this;
    }

    public void open(Player p) { p.openInventory(inventory); }

    public int size() { return inventory.getSize(); }

    void click(Player p, int slot) {
        Consumer<Player> a = actions.get(slot);
        if (a != null) a.accept(p);
    }

    @Override
    public Inventory getInventory() { return inventory; }

    // ---- item helpers ------------------------------------------------------------

    public static ItemStack item(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m == null || !m.isItem() ? Material.PAPER : m);
        it.editMeta(meta -> {
            meta.displayName(text(name));
            if (lore.length > 0) meta.lore(Arrays.stream(lore).map(Menu::text).toList());
            meta.addItemFlags(ItemFlag.values());
        });
        return it;
    }

    public static ItemStack glow(ItemStack it) {
        it.editMeta(m -> m.setEnchantmentGlintOverride(true));
        return it;
    }

    public static ItemStack head(OfflinePlayer owner, String name, String... lore) {
        ItemStack it = item(Material.PLAYER_HEAD, name, lore);
        it.editMeta(SkullMeta.class, m -> m.setOwningPlayer(owner));
        return it;
    }

    private static Component text(String mini) {
        return Msg.c("<white>" + mini).decoration(TextDecoration.ITALIC, false);
    }
}
