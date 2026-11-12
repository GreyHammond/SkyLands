package dev.skyrealms.island;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;

/** Owns every island, the sky grid they sit on, and saving/loading islands.yml. */
public final class IslandManager {
    private final File file;
    private final Logger log;
    private final int spacing;
    private final Map<UUID, Island> byOwner = new HashMap<>();
    private final Map<Long, Island> byCell = new HashMap<>();
    private final Map<UUID, Island> byCoOwner = new HashMap<>();
    private int nextIndex = 1;

    public IslandManager(File file, int spacing, Logger log) {
        this.file = file;
        this.spacing = spacing;
        this.log = log;
    }

    public Island ofOwner(UUID owner) { return byOwner.get(owner); }

    /** The island a player owns or co-owns. */
    public Island of(UUID player) {
        Island i = byOwner.get(player);
        return i != null ? i : byCoOwner.get(player);
    }

    public void addCoOwner(Island island, UUID player) {
        island.coOwners.add(player);
        island.friends.remove(player);
        byCoOwner.put(player, island);
    }

    public void removeCoOwner(Island island, UUID player) {
        island.coOwners.remove(player);
        byCoOwner.remove(player, island);
    }

    /** Hand the island to a new owner. The old owner loses all access (becomes a visitor). */
    public void transfer(Island island, UUID newOwner) {
        UUID old = island.owner;
        byOwner.remove(old);
        island.coOwners.remove(newOwner);
        byCoOwner.remove(newOwner, island);
        island.friends.remove(newOwner);
        island.friends.remove(old);
        island.owner = newOwner;
        byOwner.put(newOwner, island);
    }

    /** Islands where this player is a friend. */
    public List<Island> friendOf(UUID player) {
        return byOwner.values().stream().filter(i -> i.friends.contains(player)).toList();
    }

    public Collection<Island> all() { return byOwner.values(); }

    /** The island whose buildable area covers this X/Z, if any. */
    public Island at(double x, double z) {
        int gx = Math.round((float) (x / spacing));
        int gz = Math.round((float) (z / spacing));
        Island i = byCell.get(key(gx, gz));
        return i != null && i.containsLand(x, z) ? i : null;
    }

    public Island create(UUID owner, Tier tier) {
        int index = nextIndex++;
        int[] cell = spiral(index);
        Island island = new Island(owner, index, cell[0] * spacing, cell[1] * spacing, tier);
        register(island);
        return island;
    }

    /** Put an island (e.g. one restored from an archive) back on the grid. */
    public void restore(Island island) {
        register(island);
        nextIndex = Math.max(nextIndex, island.index + 1);
    }

    private void register(Island island) {
        byOwner.put(island.owner, island);
        for (UUID c : island.coOwners) byCoOwner.put(c, island);
        int[] cell = spiral(island.index);
        byCell.put(key(cell[0], cell[1]), island);
    }

    /** Forget an island. Its grid cell isn't reused, so a half-cleared area never gets a new owner. */
    public void remove(Island island) {
        byOwner.remove(island.owner);
        for (UUID c : island.coOwners) byCoOwner.remove(c, island);
        int[] cell = spiral(island.index);
        byCell.remove(key(cell[0], cell[1]), island);
    }

    private static long key(int gx, int gz) { return ((long) gx << 32) | (gz & 0xffffffffL); }

    /** Square spiral around the origin: 0 -> (0,0), 1 -> (1,0), 2 -> (1,1), ... Index 0 is left free for a spawn. */
    static int[] spiral(int n) {
        if (n == 0) return new int[]{0, 0};
        int k = (int) Math.ceil((Math.sqrt(n + 1) - 1) / 2);
        int t = 2 * k + 1;
        int m = t * t;
        t -= 1;
        if (n >= m - t) return new int[]{k - (m - n), -k};
        m -= t;
        if (n >= m - t) return new int[]{-k, -k + (m - n)};
        m -= t;
        if (n >= m - t) return new int[]{-k + (m - n), k};
        return new int[]{k, k - (m - n - t)};
    }

    // ---- persistence -------------------------------------------------------

    public void load() {
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        nextIndex = yml.getInt("next-index", 1);
        ConfigurationSection sec = yml.getConfigurationSection("islands");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            try {
                Island i = read(UUID.fromString(key), sec.getConfigurationSection(key));
                register(i);
                nextIndex = Math.max(nextIndex, i.index + 1);
            } catch (Exception e) {
                log.warning("Skipping broken island entry " + key + ": " + e.getMessage());
            }
        }
        log.info("Loaded " + byOwner.size() + " islands.");
    }

    /** Read one island from a config section (islands.yml entry or an archive's meta file). */
    public static Island read(UUID owner, ConfigurationSection s) {
        Tier tier = Tier.parse(s.getString("tier", "SMALL"));
        Island i = new Island(owner, s.getInt("index"), s.getInt("center-x"), s.getInt("center-z"),
                tier == null ? Tier.SMALL : tier);
        for (String m : s.getStringList("co-owners")) i.coOwners.add(UUID.fromString(m));
        for (String m : s.getStringList("friends")) i.friends.add(UUID.fromString(m));
        for (String m : s.getStringList("members")) i.friends.add(UUID.fromString(m)); // v0.1 name
        loadFlags(s, "flags.visitors", i, Role.VISITOR);
        loadFlags(s, "flags.friends", i, Role.FRIEND);
        loadFlags(s, "flags.island", i, null);
        i.biomes.addAll(s.getStringList("biomes"));
        for (Map<?, ?> p : s.getMapList("pieces")) i.pieces.add(PiecePlacement.deserialize(p));
        List<Double> home = s.getDoubleList("home");
        if (home.size() == 5) i.home = home.stream().mapToDouble(Double::doubleValue).toArray();
        i.pruneExempt = s.getBoolean("prune-exempt", false);
        i.xp = s.getLong("xp", 0);
        i.visitorsTotal = s.getLong("visitors", 0);
        i.xpBackfilled = s.getBoolean("xp-backfilled", false);
        return i;
    }

    /** Write one island into a config section. */
    public static void write(ConfigurationSection s, Island i) {
        s.set("index", i.index);
        s.set("center-x", i.centerX);
        s.set("center-z", i.centerZ);
        s.set("tier", i.tier.name());
        s.set("co-owners", i.coOwners.stream().map(UUID::toString).toList());
        s.set("friends", i.friends.stream().map(UUID::toString).toList());
        s.set("flags.visitors", i.flags(Role.VISITOR).stream().map(Enum::name).toList());
        s.set("flags.friends", i.flags(Role.FRIEND).stream().map(Enum::name).toList());
        s.set("flags.island", i.flags(null).stream().map(Enum::name).toList());
        s.set("biomes", new ArrayList<>(i.biomes));
        s.set("pieces", i.pieces.stream().map(PiecePlacement::serialize).toList());
        if (i.home != null) s.set("home", Arrays.stream(i.home).boxed().toList());
        if (i.pruneExempt) s.set("prune-exempt", true);
        s.set("xp", i.xp);
        s.set("visitors", i.visitorsTotal);
        s.set("xp-backfilled", i.xpBackfilled);
    }

    private static void loadFlags(ConfigurationSection s, String path, Island i, Role role) {
        if (!s.isList(path)) return; // keep defaults
        i.flags(role).clear();
        for (String f : s.getStringList(path)) {
            Flag flag = Flag.parse(f);
            if (flag != null) i.flags(role).add(flag);
        }
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("next-index", nextIndex);
        for (Island i : byOwner.values()) write(yml.createSection("islands." + i.owner), i);
        try {
            yml.save(file);
        } catch (IOException e) {
            log.severe("Could not save islands.yml: " + e.getMessage());
        }
    }
}
