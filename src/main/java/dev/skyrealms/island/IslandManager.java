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
    private final Map<UUID, Island> byMember = new HashMap<>();
    private int nextIndex = 1;

    public IslandManager(File file, int spacing, Logger log) {
        this.file = file;
        this.spacing = spacing;
        this.log = log;
    }

    public Island ofOwner(UUID owner) { return byOwner.get(owner); }

    /** The island a player owns, or failing that, one they're a member of. */
    public Island of(UUID player) {
        Island i = byOwner.get(player);
        return i != null ? i : byMember.get(player);
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

    public void addMember(Island island, UUID member) {
        island.members.add(member);
        byMember.put(member, island);
    }

    public void removeMember(Island island, UUID member) {
        island.members.remove(member);
        byMember.remove(member, island);
    }

    private void register(Island island) {
        byOwner.put(island.owner, island);
        int[] cell = spiral(island.index);
        byCell.put(key(cell[0], cell[1]), island);
        for (UUID m : island.members) byMember.put(m, island);
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
            ConfigurationSection s = sec.getConfigurationSection(key);
            try {
                UUID owner = UUID.fromString(key);
                Tier tier = Tier.parse(s.getString("tier", "SMALL"));
                Island i = new Island(owner, s.getInt("index"), s.getInt("center-x"), s.getInt("center-z"),
                        tier == null ? Tier.SMALL : tier);
                for (String m : s.getStringList("members")) i.members.add(UUID.fromString(m));
                i.biomes.addAll(s.getStringList("biomes"));
                for (Map<?, ?> p : s.getMapList("pieces")) i.pieces.add(PiecePlacement.deserialize(p));
                List<Double> home = s.getDoubleList("home");
                if (home.size() == 5) i.home = home.stream().mapToDouble(Double::doubleValue).toArray();
                register(i);
                nextIndex = Math.max(nextIndex, i.index + 1);
            } catch (Exception e) {
                log.warning("Skipping broken island entry " + key + ": " + e.getMessage());
            }
        }
        log.info("Loaded " + byOwner.size() + " islands.");
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("next-index", nextIndex);
        for (Island i : byOwner.values()) {
            String p = "islands." + i.owner + ".";
            yml.set(p + "index", i.index);
            yml.set(p + "center-x", i.centerX);
            yml.set(p + "center-z", i.centerZ);
            yml.set(p + "tier", i.tier.name());
            yml.set(p + "members", i.members.stream().map(UUID::toString).toList());
            yml.set(p + "biomes", new ArrayList<>(i.biomes));
            yml.set(p + "pieces", i.pieces.stream().map(PiecePlacement::serialize).toList());
            if (i.home != null) yml.set(p + "home", Arrays.stream(i.home).boxed().toList());
        }
        try {
            yml.save(file);
        } catch (IOException e) {
            log.severe("Could not save islands.yml: " + e.getMessage());
        }
    }
}
