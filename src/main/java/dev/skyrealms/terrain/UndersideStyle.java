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
package dev.skyrealms.terrain;

import org.bukkit.Material;

import java.util.Locale;

/**
 * What the rock under an island is made of, and what hangs from its bottom.
 * Chosen per island and applied to land grown afterwards (existing land is never changed).
 */
public enum UndersideStyle {
    ROCK("rock", "Rock", Material.STONE, "Classic stone with ores"),
    ROOTS("roots", "Roots", Material.ROOTED_DIRT, "Earthy dirt with roots dangling below"),
    ICE("ice", "Ice", Material.PACKED_ICE, "Packed and blue ice, frozen solid"),
    LUSH("lush", "Lush", Material.MOSS_BLOCK, "Moss with glowing berry vines and spore blossoms"),
    DRIPSTONE("dripstone", "Dripstone", Material.POINTED_DRIPSTONE, "Dripstone with stalactites hanging down"),
    CRYSTAL("crystal", "Crystal", Material.AMETHYST_CLUSTER, "Amethyst and calcite with hanging crystals"),
    NETHER("nether", "Nether", Material.CRIMSON_NYLIUM, "Blackstone and magma with weeping vines"),
    RUINS("ruins", "Ruins", Material.CRACKED_STONE_BRICKS, "Old stone bricks with lanterns on chains");

    public final String id, displayName, description;
    public final Material icon;

    UndersideStyle(String id, String displayName, Material icon, String description) {
        this.id = id;
        this.displayName = displayName;
        this.icon = icon;
        this.description = description;
    }

    /**
     * Body block. r is uniform 0..1, p is smooth noise (-1..1) for patches, nearBottom is
     * true for the lowest couple of blocks.
     */
    public Material body(double r, double p, boolean nearBottom) {
        return switch (this) {
            case ROCK -> null; // the generator's normal stone + ores
            case ROOTS -> r < 0.12 ? Material.ROOTED_DIRT : r < 0.18 ? Material.COARSE_DIRT : p > 0.5 ? Material.MUD : Material.DIRT;
            case ICE -> p > 0.45 ? Material.BLUE_ICE : r < 0.15 ? Material.SNOW_BLOCK : Material.PACKED_ICE;
            case LUSH -> nearBottom ? Material.MOSS_BLOCK : r < 0.2 ? Material.MOSS_BLOCK : p > 0.5 ? Material.CLAY : Material.STONE;
            case DRIPSTONE -> p > 0.2 ? Material.DRIPSTONE_BLOCK : r < 0.2 ? Material.TUFF : Material.STONE;
            case CRYSTAL -> r < 0.04 ? Material.BUDDING_AMETHYST : p > 0.35 ? Material.AMETHYST_BLOCK
                    : p < -0.4 ? Material.SMOOTH_BASALT : Material.CALCITE;
            case NETHER -> r < 0.06 ? Material.MAGMA_BLOCK : r < 0.08 ? Material.NETHER_QUARTZ_ORE
                    : p > 0.45 ? Material.BASALT : p < -0.45 ? Material.NETHERRACK : Material.BLACKSTONE;
            case RUINS -> r < 0.25 ? Material.MOSSY_STONE_BRICKS : r < 0.45 ? Material.CRACKED_STONE_BRICKS
                    : r < 0.48 ? Material.CHISELED_STONE_BRICKS : Material.STONE_BRICKS;
        };
    }

    /**
     * Something to hang under the island's bottom block, as block-data strings from the top down,
     * or null. r is uniform 0..1, len is a random length 1..4.
     */
    public String[] hang(double r, int len) {
        return switch (this) {
            case ROOTS -> r < 0.18 ? new String[]{"minecraft:hanging_roots"} : null;
            case LUSH -> r < 0.10 ? vines(len) : r < 0.115 ? new String[]{"minecraft:spore_blossom"} : null;
            case DRIPSTONE -> r < 0.12 ? dripstone(len) : null;
            case CRYSTAL -> r < 0.10 ? new String[]{"minecraft:amethyst_cluster[facing=down]"} : null;
            case NETHER -> r < 0.10 ? weeping(len) : r < 0.115 ? new String[]{"minecraft:shroomlight"} : null;
            case RUINS -> r < 0.012 ? new String[]{"minecraft:chain", "minecraft:chain", "minecraft:lantern[hanging=true]"} : null;
            case ICE -> r < 0.05 ? new String[]{"minecraft:packed_ice"} : null;
            default -> null;
        };
    }

    static String[] vines(int len) {
        String[] out = new String[len];
        for (int i = 0; i < len; i++) out[i] = i == len - 1 ? "minecraft:cave_vines[berries=true]" : "minecraft:cave_vines_plant[berries=" + (i % 2 == 0) + "]";
        return out;
    }

    private static String[] weeping(int len) {
        String[] out = new String[len];
        for (int i = 0; i < len; i++) out[i] = i == len - 1 ? "minecraft:weeping_vines" : "minecraft:weeping_vines_plant";
        return out;
    }

    private static String[] dripstone(int len) {
        len = Math.min(3, len);
        String[] out = new String[len];
        for (int i = 0; i < len; i++) {
            String t = i == len - 1 ? "tip" : (len == 3 && i == 0 ? "frustum" : "middle");
            if (len == 1) t = "tip";
            out[i] = "minecraft:pointed_dripstone[vertical_direction=down,thickness=" + t + "]";
        }
        return out;
    }

    public static UndersideStyle byId(String id) {
        if (id == null) return ROCK;
        String k = id.toLowerCase(Locale.ROOT);
        for (UndersideStyle s : values()) if (s.id.equals(k)) return s;
        return ROCK;
    }
}
