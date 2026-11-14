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
package dev.skyrealms.bedrock;

import dev.skyrealms.SkyRealmsPlugin;
import dev.skyrealms.contracts.Contract;
import dev.skyrealms.contracts.ContractMenu;
import dev.skyrealms.contracts.ContractService;
import dev.skyrealms.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.util.FormImage;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Daily Contracts as a native Bedrock form. Only loaded when Floodgate forms are active.
 */
public final class ContractForms {
    private static final String G = "§a", Y = "§e", P = "§d", O = "§6", GR = "§7", DG = "§8", W = "§f";

    private final SkyRealmsPlugin plugin;
    private final ContractService svc;
    private final boolean icons;

    private ContractForms(SkyRealmsPlugin plugin, ContractService svc) {
        this.plugin = plugin;
        this.svc = svc;
        this.icons = plugin.getConfig().getBoolean("bedrock.button-icons", true);
    }

    /** Wires the form into the Bedrock Corruption hub and the contract menu's Bedrock route. */
    public static void register(SkyRealmsPlugin plugin, ContractService svc, ContractMenu menu) {
        ContractForms f = new ContractForms(plugin, svc);
        menu.setBedrockOpener(f::open);
        FormMenus.corruptionButtons.add(p -> {
            int ready = 0;
            for (Contract c : svc.contracts(p)) if (!c.claimed() && c.complete()) ready++;
            return FormMenus.hubButton(O + "Daily Contracts\n" + DG + (ready > 0 ? ready + " ready to claim!" : "Jobs that pay out every day"),
                    "textures/items/book_writable", f::open);
        });
    }

    public void open(Player p) {
        List<Contract> list = svc.contracts(p);
        double bonus = svc.bonus();
        StringBuilder content = new StringBuilder(W + "New contracts in " + ContractService.duration(svc.secondsUntilReset()) + ".");
        if (bonus > 1) content.append("\n").append(P).append("Recycle soon: rewards x").append(bonus);
        List<String> labels = new ArrayList<>();
        List<Consumer<Player>> actions = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Contract c = list.get(i);
            final int index = i;
            String state = c.claimed() ? DG + "Claimed" : c.complete() ? G + "Tap to claim" : (c.type.handIn() ? Y + "Tap to hand in" : GR + c.bar());
            String reward = O + plugin.service().money(c.reward * bonus) + (c.shards > 0 ? P + " +" + c.shards + " shards" : "");
            labels.add((c.claimed() ? DG : W) + c.title() + "\n" + state + DG + " · " + reward);
            actions.add(c.claimed() ? pl -> open(pl) : pl -> {
                String msg = svc.use(pl, index);
                if (msg != null) Msg.send(pl, msg);
                open(pl);
            });
            content.append("\n\n").append(W).append(c.title()).append("\n").append(GR).append(c.bar());
        }
        labels.add(GR + "Back");
        actions.add(pl -> plugin.menus().corruption(pl));

        SimpleForm.Builder f = SimpleForm.builder().title("Daily Contracts").content(content.toString());
        for (int i = 0; i < labels.size(); i++) {
            if (icons && i == labels.size() - 1) f.button(labels.get(i));
            else if (icons) f.button(labels.get(i), FormImage.Type.PATH, "textures/items/paper");
            else f.button(labels.get(i));
        }
        f.validResultHandler(r -> sync(p, () -> {
            int id = r.clickedButtonId();
            if (id >= 0 && id < actions.size()) actions.get(id).accept(p);
        }));
        var form = f.build();
        var hook = FormMenus.testSender;
        if (hook != null) hook.accept(p, form);
        else FloodgateApi.getInstance().sendForm(p.getUniqueId(), form);
    }

    private void sync(Player p, Runnable r) {
        if (Bukkit.isPrimaryThread()) { if (p.isOnline()) r.run(); return; }
        Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) r.run(); });
    }
}
