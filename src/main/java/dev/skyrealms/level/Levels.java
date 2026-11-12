package dev.skyrealms.level;

import dev.skyrealms.island.Tier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;

/** Everything in levels.yml: the XP curve, XP amounts, titles, biome unlock levels and rewards. */
public final class Levels {
    public final int maxLevel;
    private final double a, b;
    public final int buyPerPiece, growPiece, unlockBiome, placeBlock, placeCap, harvestCrop, harvestCap, visitor, visitorCap;
    private final Map<Tier, Integer> upgradeXp = new EnumMap<>(Tier.class);
    private final Map<String, Integer> biomeLevels = new HashMap<>(Map.ofEntries(
            Map.entry("plains", 1), Map.entry("oak_forest", 1), Map.entry("desert", 1), Map.entry("beach", 1),
            Map.entry("meadow", 5), Map.entry("hilly_plains", 5), Map.entry("shallow_ocean", 5), Map.entry("cherry_grove", 5),
            Map.entry("jungle", 15), Map.entry("badlands", 15), Map.entry("swamp", 15), Map.entry("snowy", 15),
            Map.entry("dark_oak_forest", 30), Map.entry("deep_ocean", 30), Map.entry("mushroom", 30), Map.entry("lush_caverns", 30),
            Map.entry("volcanic", 50), Map.entry("crystal", 50), Map.entry("ancient", 50)));
    private final Map<String, Integer> undersideLevels = new HashMap<>(Map.of(
            "rock", 1, "roots", 10, "ice", 20, "lush", 30, "dripstone", 40, "crystal", 55, "nether", 65, "ruins", 75));
    private final Map<String, Map<String, Integer>> cosmeticLevels = new HashMap<>(Map.of(
            "particles", new HashMap<>(Map.of("none", 1, "fireflies", 10, "petals", 25, "snowfall", 40, "embers", 60, "sparkles", 80, "aurora", 100)),
            "border", new HashMap<>(Map.of("none", 1, "basic", 15, "leaves", 30, "glow", 45, "flame", 70)),
            "decorations", new HashMap<>(Map.of("none", 1, "few", 20, "more", 50, "ring", 90)),
            "themes", new HashMap<>(Map.of("frozen", 20, "medieval", 35, "fantasy", 45, "magical", 60, "volcanic", 70, "ancient", 80))));
    public final int nameLevel;
    private final TreeMap<Integer, String> titles = new TreeMap<>();
    private final Map<Integer, List<String>> rewards = new HashMap<>();
    public final int announceEvery;

    public Levels(File file) {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        maxLevel = Math.max(2, y.getInt("max-level", 100));
        a = y.getDouble("curve.a", 25);
        b = y.getDouble("curve.b", 75);
        buyPerPiece = y.getInt("xp.buy-per-piece", 150);
        growPiece = y.getInt("xp.grow-piece", 150);
        unlockBiome = y.getInt("xp.unlock-biome", 300);
        placeBlock = y.getInt("xp.place-block", 1);
        placeCap = y.getInt("xp.place-block-daily-cap", 1500);
        harvestCrop = y.getInt("xp.harvest-crop", 2);
        harvestCap = y.getInt("xp.harvest-daily-cap", 800);
        visitor = y.getInt("xp.unique-visitor", 25);
        visitorCap = y.getInt("xp.visitor-daily-cap", 500);
        int[] defUp = {0, 500, 1500, 4000, 10000};
        for (Tier t : Tier.values()) upgradeXp.put(t, y.getInt("xp.upgrade." + t.name(), defUp[t.ordinal()]));
        ConfigurationSection bl = y.getConfigurationSection("biome-levels");
        if (bl != null) for (String k : bl.getKeys(false)) biomeLevels.put(k.toLowerCase(Locale.ROOT), Math.max(1, bl.getInt(k)));
        ConfigurationSection ul = y.getConfigurationSection("underside-levels");
        if (ul != null) for (String k : ul.getKeys(false)) undersideLevels.put(k.toLowerCase(Locale.ROOT), Math.max(1, ul.getInt(k)));
        ConfigurationSection cl = y.getConfigurationSection("cosmetic-levels");
        if (cl != null) for (String cat : cl.getKeys(false)) {
            ConfigurationSection c = cl.getConfigurationSection(cat);
            if (c == null) continue;
            var map = cosmeticLevels.computeIfAbsent(cat.toLowerCase(Locale.ROOT), k -> new HashMap<>());
            for (String k : c.getKeys(false)) map.put(k.toLowerCase(Locale.ROOT), Math.max(1, c.getInt(k)));
        }
        nameLevel = Math.max(1, y.getInt("island-name-level", 5));
        ConfigurationSection ts = y.getConfigurationSection("titles");
        if (ts != null) for (String k : ts.getKeys(false)) {
            try { titles.put(Integer.parseInt(k), ts.getString(k)); } catch (NumberFormatException ignored) {}
        }
        if (titles.isEmpty()) titles.put(1, "Castaway");
        ConfigurationSection rs = y.getConfigurationSection("rewards");
        if (rs != null) for (String k : rs.getKeys(false)) {
            try { rewards.put(Integer.parseInt(k), rs.getStringList(k)); } catch (NumberFormatException ignored) {}
        }
        announceEvery = Math.max(0, y.getInt("announce-every", 10));
    }

    /** Total XP needed to reach this level (level 1 = 0). */
    public long xpFor(int level) {
        if (level <= 1) return 0;
        return Math.round(a * level * level + b * level);
    }

    public int levelOf(long xp) {
        int lv = 1;
        while (lv < maxLevel && xp >= xpFor(lv + 1)) lv++;
        return lv;
    }

    public String title(int level) {
        Map.Entry<Integer, String> e = titles.floorEntry(level);
        return e == null ? titles.firstEntry().getValue() : e.getValue();
    }

    /** Levels that grant a new title, in order. */
    public NavigableMap<Integer, String> titles() { return Collections.unmodifiableNavigableMap(titles); }

    public int upgradeXp(Tier t) { return upgradeXp.getOrDefault(t, 0); }

    public int biomeLevel(String biome) { return biomeLevels.getOrDefault(biome.toLowerCase(Locale.ROOT), 1); }

    public int undersideLevel(String style) { return undersideLevels.getOrDefault(style.toLowerCase(Locale.ROOT), 1); }

    /** Level needed for a cosmetic: category is particles / border / decorations / themes. */
    public int cosmeticLevel(String category, String id) {
        return cosmeticLevels.getOrDefault(category, Map.of()).getOrDefault(id.toLowerCase(Locale.ROOT), 1);
    }

    public List<String> rewards(int level) { return rewards.getOrDefault(level, List.of()); }
}
