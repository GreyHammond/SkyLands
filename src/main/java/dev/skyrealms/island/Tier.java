package dev.skyrealms.island;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;

public enum Tier {
    SMALL, MEDIUM, LARGE, EXTRA_LARGE, CONTINENTAL;

    public int footprint;
    public double price;
    public int startPieces;
    public int maxPieces;

    public static void load(ConfigurationSection tiers) {
        for (Tier t : values()) {
            ConfigurationSection s = tiers == null ? null : tiers.getConfigurationSection(t.name());
            t.footprint = s == null ? 32 << t.ordinal() : s.getInt("footprint", 32 << t.ordinal());
            t.price = s == null ? 0 : s.getDouble("price", 0);
            t.startPieces = s == null ? 1 : Math.max(1, s.getInt("start-pieces", 1));
            t.maxPieces = s == null ? t.startPieces : Math.max(t.startPieces, s.getInt("max-pieces", t.startPieces));
        }
    }

    public Tier next() { return ordinal() + 1 < values().length ? values()[ordinal() + 1] : null; }

    public String pretty() {
        String s = name().replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public static Tier parse(String s) {
        String key = s.toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (key.equals("XL")) key = "EXTRA_LARGE";
        try { return valueOf(key); } catch (IllegalArgumentException e) { return null; }
    }
}
