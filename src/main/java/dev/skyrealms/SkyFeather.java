package dev.skyrealms;

import dev.skyrealms.util.Msg;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/** The item that replaces a launch pad: right-click to ride a gust of wind home. Works for Bedrock (tap/hold) too. */
public final class SkyFeather implements Listener {
    private final SkyRealmsPlugin plugin;
    private final NamespacedKey key;
    private final Material material;

    public SkyFeather(SkyRealmsPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "sky_feather");
        Material m = Material.matchMaterial(plugin.getConfig().getString("sky-feather.material", "FEATHER"));
        this.material = m == null || !m.isItem() ? Material.FEATHER : m;
    }

    public ItemStack create() {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(Msg.c("<aqua>Sky Feather").decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Msg.c("<gray>Use it to ride the wind").decoration(TextDecoration.ITALIC, false),
                    Msg.c("<gray>back up to your island.").decoration(TextDecoration.ITALIC, false)));
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        });
        return item;
    }

    public boolean is(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!is(e.getItem())) return;
        e.setCancelled(true);
        plugin.service().launchHome(e.getPlayer());
    }
}
