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
import org.bukkit.configuration.ConfigurationSection;

import java.util.List;
import java.util.Locale;

/** What a contract asks for. */
public enum ContractType {
    DELIVER, KILL, KILL_CORRUPTED, MINE, EXPLORE, SHARDS;

    public static ContractType parse(String s) {
        if (s == null) return null;
        try { return valueOf(s.trim().toUpperCase(Locale.ROOT).replace('-', '_')); } catch (IllegalArgumentException e) { return null; }
    }

    /** True for contracts finished by handing items in from the inventory. */
    public boolean handIn() { return this == DELIVER || this == SHARDS; }

    /** One entry of the configured contract pool. */
    public record Template(String id, ContractType type, Material item, int minAmount, int maxAmount,
                           double minReward, double maxReward, int minShards, int maxShards, int weight) {
        static Template load(String id, ConfigurationSection c) {
            ContractType t = parse(c.getString("type"));
            if (t == null) return null;
            Material m = null;
            if (t == DELIVER) {
                m = Material.matchMaterial(c.getString("item", ""));
                if (m == null || !m.isItem()) return null;
            }
            int[] a = range(c.getIntegerList("amount"), 10, 20);
            int[] s = range(c.getIntegerList("shards"), 0, 0);
            List<Double> r = c.getDoubleList("reward");
            double r0 = r.isEmpty() ? 100 : r.get(0), r1 = r.size() > 1 ? r.get(1) : r0;
            return new Template(id, t, m, Math.max(1, a[0]), Math.max(a[0], a[1]), Math.max(0, r0), Math.max(r0, r1),
                    Math.max(0, s[0]), Math.max(s[0], s[1]), Math.max(0, c.getInt("weight", 10)));
        }

        private static int[] range(List<Integer> l, int d0, int d1) {
            if (l.isEmpty()) return new int[]{d0, d1};
            return new int[]{l.get(0), l.size() > 1 ? l.get(1) : l.get(0)};
        }
    }
}
