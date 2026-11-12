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
    private final Map<String, Integer> biomeLevels = new HashMap<>();
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

    public List<String> rewards(int level) { return rewards.getOrDefault(level, List.of()); }
}
