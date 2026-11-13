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

import org.bukkit.Material;

import java.util.Locale;

/** The cosmetic options. Unlock levels live in Levels (levels.yml, with built-in defaults). */
public final class Cosmetics {
    private Cosmetics() {}

    public enum ParticleSet {
        NONE("none", "None", Material.BARRIER, "No particles"),
        FIREFLIES("fireflies", "Fireflies", Material.GLOWSTONE_DUST, "Little lights drifting in the air"),
        PETALS("petals", "Petals", Material.PINK_PETALS, "Cherry petals floating down"),
        SNOWFALL("snowfall", "Snowfall", Material.SNOWBALL, "Gentle snowflakes"),
        EMBERS("embers", "Embers", Material.BLAZE_POWDER, "Glowing embers and ash"),
        SPARKLES("sparkles", "Sparkles", Material.AMETHYST_SHARD, "Magical sparkles"),
        AURORA("aurora", "Aurora", Material.PRISMARINE_CRYSTALS, "Shimmering lights high in the sky");

        public final String id, displayName, description;
        public final Material icon;
        ParticleSet(String id, String n, Material icon, String d) { this.id = id; this.displayName = n; this.icon = icon; this.description = d; }
        public static ParticleSet byId(String id) { for (var v : values()) if (v.id.equalsIgnoreCase(id == null ? "" : id)) return v; return NONE; }
    }

    public enum BorderStyle {
        NONE("none", "Hidden", Material.BARRIER, "No border lights"),
        BASIC("basic", "Basic", Material.WHITE_DYE, "A soft white line at your border"),
        LEAVES("leaves", "Leaves", Material.OAK_LEAVES, "A green line of leaves"),
        GLOW("glow", "Glow", Material.GLOW_INK_SAC, "A glowing aqua line"),
        FLAME("flame", "Flame", Material.BLAZE_POWDER, "A line of little flames");

        public final String id, displayName, description;
        public final Material icon;
        BorderStyle(String id, String n, Material icon, String d) { this.id = id; this.displayName = n; this.icon = icon; this.description = d; }
        public static BorderStyle byId(String id) { for (var v : values()) if (v.id.equalsIgnoreCase(id == null ? "" : id)) return v; return NONE; }
    }

    public enum DecorationSet {
        NONE("none", "None", Material.BARRIER, "No floating decorations", 0),
        FEW("few", "A Few Islets", Material.MOSS_BLOCK, "3 little floating islets around your island", 3),
        MORE("more", "Many Islets", Material.GRASS_BLOCK, "7 floating islets, some with trees", 7),
        RING("ring", "Rock Ring", Material.COBBLESTONE, "Islets plus a ring of drifting rocks", 7);

        public final String id, displayName, description;
        public final Material icon;
        public final int islets;
        DecorationSet(String id, String n, Material icon, String d, int islets) { this.id = id; this.displayName = n; this.icon = icon; this.description = d; this.islets = islets; }
        public static DecorationSet byId(String id) { for (var v : values()) if (v.id.equalsIgnoreCase(id == null ? "" : id)) return v; return NONE; }
    }

    public enum Theme {
        FROZEN("frozen", "Frozen", Material.BLUE_ICE, "ice", ParticleSet.SNOWFALL, BorderStyle.BASIC),
        MEDIEVAL("medieval", "Medieval", Material.STONE_BRICKS, "ruins", ParticleSet.NONE, BorderStyle.BASIC),
        FANTASY("fantasy", "Fantasy", Material.FLOWERING_AZALEA, "lush", ParticleSet.FIREFLIES, BorderStyle.GLOW),
        MAGICAL("magical", "Magical", Material.AMETHYST_CLUSTER, "crystal", ParticleSet.SPARKLES, BorderStyle.GLOW),
        VOLCANIC("volcanic", "Volcanic", Material.MAGMA_BLOCK, "nether", ParticleSet.EMBERS, BorderStyle.FLAME),
        ANCIENT("ancient", "Ancient", Material.CHISELED_STONE_BRICKS, "ruins", ParticleSet.PETALS, BorderStyle.LEAVES);

        public final String id, displayName, underside;
        public final Material icon;
        public final ParticleSet particles;
        public final BorderStyle border;
        Theme(String id, String n, Material icon, String underside, ParticleSet p, BorderStyle b) {
            this.id = id; this.displayName = n; this.icon = icon; this.underside = underside; this.particles = p; this.border = b;
        }
        public static Theme byId(String id) {
            for (var v : values()) if (v.id.equalsIgnoreCase(id == null ? "" : id)) return v;
            return null;
        }
    }

    public static String category(Enum<?> e) {
        return switch (e) {
            case ParticleSet p -> "particles";
            case BorderStyle b -> "border";
            case DecorationSet d -> "decorations";
            case Theme t -> "themes";
            default -> e.getDeclaringClass().getSimpleName().toLowerCase(Locale.ROOT);
        };
    }
}
