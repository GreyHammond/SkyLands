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

import org.bukkit.Material;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** One of a player's daily contracts. */
public final class Contract {
    public final ContractType type;
    public final Material item; // DELIVER only
    public final int amount;
    public final double reward;
    public final int shards;
    int progress;
    boolean claimed;
    /** EXPLORE: chunk keys already counted. */
    final Set<Long> visited = new HashSet<>();

    Contract(ContractType type, Material item, int amount, double reward, int shards) {
        this.type = type;
        this.item = item;
        this.amount = amount;
        this.reward = reward;
        this.shards = shards;
    }

    public int progress() { return progress; }
    public boolean claimed() { return claimed; }
    public boolean complete() { return progress >= amount; }

    public String title() {
        return switch (type) {
            case DELIVER -> "Deliver " + amount + " " + pretty(item);
            case KILL -> "Slay " + amount + " hostile mobs in the overworld";
            case KILL_CORRUPTED -> "Slay " + amount + " corrupted mobs";
            case MINE -> "Mine " + amount + " blocks in the overworld";
            case EXPLORE -> "Explore " + amount + " new chunks in the overworld";
            case SHARDS -> "Hand in " + amount + " Void Shards";
        };
    }

    /** "■■■■□□□□□□ 12/30" */
    public String bar() {
        int filled = (int) Math.floor(10.0 * Math.min(progress, amount) / amount);
        return "■".repeat(filled) + "□".repeat(10 - filled) + " " + Math.min(progress, amount) + "/" + amount;
    }

    static String pretty(Material m) {
        if (m == null) return "items";
        String[] words = m.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder b = new StringBuilder();
        for (String w : words) { if (b.length() > 0) b.append(' '); b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)); }
        return b.toString();
    }
}
