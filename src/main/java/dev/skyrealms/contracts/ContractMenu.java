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
package dev.skyrealms.contracts;

import dev.skyrealms.SkyRealmsPlugin;
import dev.skyrealms.menu.Menu;
import dev.skyrealms.util.Msg;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static dev.skyrealms.menu.Menu.glow;
import static dev.skyrealms.menu.Menu.item;

/** The Daily Contracts chest menu, plus the routing to the Bedrock form. */
public final class ContractMenu {
    private final SkyRealmsPlugin plugin;
    private final ContractService svc;
    /** Opens the Bedrock form; set at startup only when Floodgate forms are active. */
    private Consumer<Player> bedrockOpener;

    public ContractMenu(SkyRealmsPlugin plugin, ContractService svc) {
        this.plugin = plugin;
        this.svc = svc;
    }

    public void setBedrockOpener(Consumer<Player> opener) { this.bedrockOpener = opener; }

    /** Adds the Contracts button to the Corruption hub. */
    public void hook() {
        plugin.menus().corruptionButtons.add((p, m) -> {
            int done = 0, ready = 0;
            List<Contract> list = svc.contracts(p);
            for (Contract c : list) { if (c.claimed()) done++; else if (c.complete()) ready++; }
            ItemStack icon = item(Material.WRITABLE_BOOK, "<gold>Daily Contracts", "<gray>Jobs that pay out every day.",
                    "<gray>Claimed: <white>" + done + "/" + list.size(),
                    ready > 0 ? "<green>" + ready + " ready to claim!" : "<gray>New contracts in <white>" + ContractService.duration(svc.secondsUntilReset()));
            m.button(15, ready > 0 ? glow(icon) : icon, this::open);
        });
    }

    public void open(Player p) {
        if (bedrockOpener != null && plugin.bedrock().handles(p)) { bedrockOpener.accept(p); return; }
        List<Contract> list = svc.contracts(p);
        Menu m = new Menu(3, "<gold>Daily Contracts");
        double bonus = svc.bonus();
        m.button(4, item(Material.CLOCK, "<gold>Daily Contracts", "<gray>New contracts in <white>" + ContractService.duration(svc.secondsUntilReset()),
                bonus > 1 ? "<light_purple>Recycle soon: rewards x" + trim(bonus) : "<dark_gray>Rewards rise the day before a recycle."), null);
        int[] slots = list.size() <= 3 ? new int[]{11, 13, 15} : new int[]{10, 11, 12, 13, 14, 15, 16};
        for (int i = 0; i < list.size() && i < slots.length; i++) {
            Contract c = list.get(i);
            final int index = i;
            m.button(slots[i], icon(c, bonus), c.claimed() ? null : pl -> {
                String msg = svc.use(pl, index);
                if (msg != null) Msg.send(pl, msg);
                open(pl);
            });
        }
        m.button(18, item(Material.ARROW, "<white>Back"), pl -> plugin.menus().corruption(pl));
        m.fill().open(p);
    }

    private ItemStack icon(Contract c, double bonus) {
        Material mat = switch (c.type) {
            case DELIVER -> c.item;
            case KILL -> Material.IRON_SWORD;
            case KILL_CORRUPTED -> Material.NETHERITE_SWORD;
            case MINE -> Material.IRON_PICKAXE;
            case EXPLORE -> Material.FILLED_MAP;
            case SHARDS -> Material.ECHO_SHARD;
        };
        List<String> lore = new ArrayList<>();
        lore.add((c.complete() ? "<green>" : "<yellow>") + c.bar());
        lore.add("<gray>Reward: <gold>" + plugin.service().money(c.reward * bonus)
                + (c.shards > 0 ? " <dark_purple>+" + c.shards + " Void Shards" : ""));
        if (c.claimed()) lore.add("<dark_gray>Claimed");
        else if (c.complete()) lore.add("<green>Click to claim");
        else if (c.type.handIn()) lore.add("<yellow>Click to hand in what you carry");
        else lore.add("<dark_gray>Progress counts in the overworld");
        ItemStack it = item(c.claimed() ? Material.GRAY_DYE : mat, (c.claimed() ? "<dark_gray>" : "<white>") + c.title(), lore.toArray(String[]::new));
        return c.complete() && !c.claimed() ? glow(it) : it;
    }

    static String trim(double d) { return d == Math.floor(d) ? String.valueOf((int) d) : String.valueOf(d); }
}
