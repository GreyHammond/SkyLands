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
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Decides whether a player gets native Bedrock forms or the chest menus.
 * FormMenus (which uses the Floodgate API) is only loaded when Floodgate is installed,
 * so the plugin runs fine without it.
 */
public final class BedrockUi {
    private final Object forms; // FormMenus, typed loosely so the class isn't loaded without Floodgate

    public BedrockUi(SkyRealmsPlugin plugin) {
        boolean enabled = plugin.getConfig().getBoolean("bedrock.forms", true);
        boolean floodgate = Bukkit.getPluginManager().isPluginEnabled("floodgate");
        Object f = null;
        if (enabled && floodgate) {
            try {
                f = new FormMenus(plugin);
                plugin.getLogger().info("Floodgate found: Bedrock players get native forms.");
            } catch (Throwable t) {
                plugin.getLogger().warning("Floodgate found but forms couldn't start, using chest menus: " + t);
            }
        }
        this.forms = f;
    }

    public boolean active() { return forms != null; }

    /** True if this player should see forms instead of chest menus. */
    public boolean handles(Player p) {
        if (forms == null) return false;
        try { return FormMenus.isBedrock(p); } catch (Throwable t) { return false; }
    }

    public void openMain(Player p) { ((FormMenus) forms).main(p); }
    public void openCorruption(Player p) { ((FormMenus) forms).corruption(p); }
    public void openExchange(Player p) { ((FormMenus) forms).exchange(p); }

    /** The FormMenus instance (or null), for tests and integrations. */
    public Object forms() { return forms; }
}
