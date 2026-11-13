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
package dev.skyrealms.cosmetic;

import dev.skyrealms.SkyRealmsPlugin;
import dev.skyrealms.island.Island;
import dev.skyrealms.terrain.UndersideStyle;
import dev.skyrealms.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Applies cosmetics and themes, with level checks. Owners and co-owners can change them. */
public final class CosmeticService {
    private final SkyRealmsPlugin plugin;

    public CosmeticService(SkyRealmsPlugin plugin) { this.plugin = plugin; }

    /** The island's custom name, or "<owner>'s Island". */
    public String displayName(Island i) {
        if (i.name != null && !i.name.isBlank()) return i.name;
        return Objects.toString(Bukkit.getOfflinePlayer(i.owner).getName(), "Someone") + "'s Island";
    }

    public int need(Enum<?> option) {
        String id = switch (option) {
            case Cosmetics.ParticleSet p -> p.id;
            case Cosmetics.BorderStyle b -> b.id;
            case Cosmetics.DecorationSet d -> d.id;
            case Cosmetics.Theme t -> t.id;
            default -> "";
        };
        return plugin.xp().levels().cosmeticLevel(Cosmetics.category(option), id);
    }

    private boolean check(Player p, Island i, Enum<?> option, String label) {
        int need = need(option);
        if (plugin.xp().level(i) < need) { Msg.error(p, label + " unlocks at island level " + need + "."); return false; }
        return true;
    }

    public boolean setParticles(Player p, Cosmetics.ParticleSet set) {
        Island i = plugin.service().owned(p);
        if (i == null || !check(p, i, set, set.displayName)) return false;
        i.particles = set.id;
        i.theme = null;
        plugin.islands().save();
        Msg.send(p, set == Cosmetics.ParticleSet.NONE ? "Island particles turned off." : "Your island now has <aqua>" + set.displayName + "</aqua>.");
        return true;
    }

    public boolean setBorder(Player p, Cosmetics.BorderStyle style) {
        Island i = plugin.service().owned(p);
        if (i == null || !check(p, i, style, style.displayName + " border")) return false;
        i.border = style.id;
        i.theme = null;
        plugin.islands().save();
        Msg.send(p, style == Cosmetics.BorderStyle.NONE ? "Border lights turned off."
                : "Border style set to <aqua>" + style.displayName + "</aqua>. It lights up when you're near the edge.");
        return true;
    }

    public boolean setDecorations(Player p, Cosmetics.DecorationSet set) {
        Island i = plugin.service().owned(p);
        if (i == null || !check(p, i, set, set.displayName)) return false;
        Islets.apply(plugin.skyWorld(), i, set, plugin.baseY());
        plugin.islands().save();
        Msg.send(p, set == Cosmetics.DecorationSet.NONE ? "Floating decorations removed."
                : "<aqua>" + set.displayName + "</aqua> now float around your island.");
        return true;
    }

    /** A theme sets the underside, particles and border in one go, even parts not unlocked on their own yet. */
    public boolean applyTheme(Player p, Cosmetics.Theme theme) {
        Island i = plugin.service().owned(p);
        if (i == null || !check(p, i, theme, theme.displayName + " theme")) return false;
        i.theme = theme.id;
        i.underside = theme.underside;
        i.particles = theme.particles.id;
        i.border = theme.border.id;
        plugin.islands().save();
        Msg.send(p, "<aqua>" + theme.displayName + "</aqua> theme applied: " + UndersideStyle.byId(theme.underside).displayName
                + " underside for new land, " + theme.particles.displayName.toLowerCase() + " particles, "
                + theme.border.displayName.toLowerCase() + " border.");
        return true;
    }

    /** Labels for everything cosmetic that unlocks at exactly this level (for level-up messages and the Levels screen). */
    public List<String> unlocksAt(int level) {
        var lv = plugin.xp().levels();
        List<String> out = new ArrayList<>();
        if (level > 1 && lv.nameLevel == level) out.add("Custom island name");
        for (var t : Cosmetics.Theme.values()) if (need(t) == level) out.add(t.displayName + " theme");
        for (var s : Cosmetics.ParticleSet.values()) if (s != Cosmetics.ParticleSet.NONE && need(s) == level) out.add(s.displayName + " particles");
        for (var s : Cosmetics.BorderStyle.values()) if (s != Cosmetics.BorderStyle.NONE && need(s) == level) out.add(s.displayName + " border");
        for (var s : Cosmetics.DecorationSet.values()) if (s != Cosmetics.DecorationSet.NONE && need(s) == level) out.add(s.displayName);
        return out;
    }
}
