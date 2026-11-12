package dev.skyrealms.terrain;

import org.bukkit.Material;
import org.bukkit.TreeType;

import java.util.Locale;

/**
 * The built-in island biomes. Heights are relative to sea level.
 * Ocean biomes sit below sea level and their water always tops out at exactly sea level.
 */
public enum TerrainBiome {
    //            id                 name                vanilla biome              icon                       kind        base   amp   scale
    PLAINS("plains", "Plains", "minecraft:plains", Material.GRASS_BLOCK, Kind.LAND, 3, 2.5, 0.030),
    HILLY_PLAINS("hilly_plains", "Hilly Plains", "minecraft:plains", Material.TALL_GRASS, Kind.LAND, 7, 10, 0.022),
    MEADOW("meadow", "Meadow", "minecraft:meadow", Material.CORNFLOWER, Kind.LAND, 6, 4, 0.028),
    OAK_FOREST("oak_forest", "Oak Forest", "minecraft:forest", Material.OAK_SAPLING, Kind.LAND, 4, 4, 0.035),
    DARK_OAK_FOREST("dark_oak_forest", "Dark Oak Forest", "minecraft:dark_forest", Material.DARK_OAK_SAPLING, Kind.LAND, 4, 3, 0.035),
    CHERRY_GROVE("cherry_grove", "Cherry Grove", "minecraft:cherry_grove", Material.CHERRY_SAPLING, Kind.LAND, 8, 6, 0.030),
    JUNGLE("jungle", "Jungle", "minecraft:jungle", Material.JUNGLE_SAPLING, Kind.LAND, 5, 6, 0.035),
    SWAMP("swamp", "Swamp", "minecraft:swamp", Material.LILY_PAD, Kind.LAND, 0.7, 1.6, 0.070),
    DESERT("desert", "Desert", "minecraft:desert", Material.CACTUS, Kind.LAND, 3, 4, 0.025),
    BADLANDS("badlands", "Badlands", "minecraft:badlands", Material.TERRACOTTA, Kind.LAND, 7, 12, 0.020),
    BEACH("beach", "Beach", "minecraft:beach", Material.SAND, Kind.LAND, 1.2, 0.8, 0.040),
    SNOWY("snowy", "Snowy", "minecraft:snowy_taiga", Material.SNOW_BLOCK, Kind.LAND, 5, 5, 0.030),
    MUSHROOM("mushroom", "Mushroom Fields", "minecraft:mushroom_fields", Material.RED_MUSHROOM_BLOCK, Kind.LAND, 4, 4, 0.030),
    LUSH_CAVERNS("lush_caverns", "Lush Caverns", "minecraft:lush_caves", Material.FLOWERING_AZALEA, Kind.LAND, 5, 5, 0.035),
    VOLCANIC("volcanic", "Volcanic", "minecraft:stony_peaks", Material.MAGMA_BLOCK, Kind.LAND, 9, 13, 0.025),
    CRYSTAL("crystal", "Crystal", "minecraft:stony_peaks", Material.AMETHYST_CLUSTER, Kind.LAND, 6, 7, 0.030),
    ANCIENT("ancient", "Ancient Ruins", "minecraft:old_growth_birch_forest", Material.CHISELED_STONE_BRICKS, Kind.LAND, 4, 4, 0.030),
    SHALLOW_OCEAN("shallow_ocean", "Shallow Ocean", "minecraft:lukewarm_ocean", Material.SEAGRASS, Kind.WATER, -5, 1.5, 0.040),
    DEEP_OCEAN("deep_ocean", "Deep Ocean", "minecraft:deep_ocean", Material.KELP, Kind.WATER, -18, 4, 0.030);

    public enum Kind { LAND, WATER }

    public final String id, displayName, vanillaBiome;
    public final Material icon;
    public final Kind kind;
    public final double base, amp, scale;

    TerrainBiome(String id, String displayName, String vanillaBiome, Material icon, Kind kind, double base, double amp, double scale) {
        this.id = id;
        this.displayName = displayName;
        this.vanillaBiome = vanillaBiome;
        this.icon = icon;
        this.kind = kind;
        this.base = base;
        this.amp = amp;
        this.scale = scale;
    }

    public boolean isWater() { return kind == Kind.WATER; }

    /** Can lakes form here? */
    public boolean hasLakes() {
        return this == PLAINS || this == HILLY_PLAINS || this == MEADOW || this == OAK_FOREST
                || this == CHERRY_GROVE || this == JUNGLE || this == DARK_OAK_FOREST || this == SNOWY
                || this == LUSH_CAVERNS || this == VOLCANIC || this == ANCIENT;
    }

    /** What lakes are filled with. */
    public Material liquid() { return this == VOLCANIC ? Material.LAVA : Material.WATER; }

    /** Exact surface block data when a plain Material isn't enough (e.g. snowy grass), or null. */
    public String surfaceData(double r) {
        return this == SNOWY && r >= 0.04 ? "minecraft:grass_block[snowy=true]" : null;
    }

    public Material surface(double r) {
        return switch (this) {
            case DESERT, BEACH -> Material.SAND;
            case BADLANDS -> Material.RED_SAND;
            case DARK_OAK_FOREST -> r < 0.08 ? Material.COARSE_DIRT : Material.GRASS_BLOCK;
            case SWAMP -> r < 0.12 ? Material.MUD : Material.GRASS_BLOCK;
            case JUNGLE -> r < 0.05 ? Material.PODZOL : Material.GRASS_BLOCK;
            case SNOWY -> r < 0.04 ? Material.PODZOL : Material.GRASS_BLOCK;
            case MUSHROOM -> Material.MYCELIUM;
            case LUSH_CAVERNS -> r < 0.55 ? Material.MOSS_BLOCK : Material.GRASS_BLOCK;
            case VOLCANIC -> r < 0.30 ? Material.BASALT : r < 0.48 ? Material.BLACKSTONE : r < 0.66 ? Material.TUFF
                    : r < 0.80 ? Material.SMOOTH_BASALT : r < 0.90 ? Material.MAGMA_BLOCK : Material.GRAVEL;
            case CRYSTAL -> r < 0.62 ? Material.CALCITE : r < 0.80 ? Material.AMETHYST_BLOCK : Material.SMOOTH_BASALT;
            case ANCIENT -> r < 0.10 ? Material.COARSE_DIRT : r < 0.18 ? Material.MOSSY_COBBLESTONE : r < 0.24 ? Material.PODZOL : Material.GRASS_BLOCK;
            case SHALLOW_OCEAN -> r < 0.25 ? Material.GRAVEL : Material.SAND;
            case DEEP_OCEAN -> r < 0.55 ? Material.GRAVEL : (r < 0.7 ? Material.CLAY : Material.SAND);
            default -> Material.GRASS_BLOCK;
        };
    }

    /** Block a few layers under the surface. depth = 1.. */
    public Material fill(int depth, int y) {
        return switch (this) {
            case DESERT, BEACH -> depth <= 3 ? Material.SAND : Material.SANDSTONE;
            case BADLANDS -> TERRACOTTA_BANDS[Math.floorMod(y, TERRACOTTA_BANDS.length)];
            case SHALLOW_OCEAN, DEEP_OCEAN -> depth <= 2 ? Material.SAND : Material.SANDSTONE;
            case SWAMP -> depth <= 1 ? Material.MUD : Material.DIRT;
            case LUSH_CAVERNS -> depth <= 1 ? Material.MOSS_BLOCK : depth <= 2 ? Material.ROOTED_DIRT : Material.DIRT;
            case VOLCANIC -> depth <= 2 ? Material.TUFF : depth <= 4 ? Material.BLACKSTONE : Material.BASALT;
            case CRYSTAL -> depth <= 2 ? Material.CALCITE : Material.SMOOTH_BASALT;
            case ANCIENT -> depth <= 1 ? Material.DIRT : Material.COBBLESTONE;
            default -> Material.DIRT;
        };
    }

    public int fillDepth() { return this == BADLANDS ? 30 : this == VOLCANIC ? 8 : 4; }

    /** Floor under shoreline or swamp water belonging to a land biome. */
    public Material wetFloor(double r) {
        return switch (this) {
            case SWAMP -> r < 0.5 ? Material.MUD : Material.CLAY;
            case BADLANDS -> Material.RED_SAND;
            default -> r < 0.15 ? Material.GRAVEL : Material.SAND;
        };
    }

    private static final Material[] TERRACOTTA_BANDS = {
            Material.TERRACOTTA, Material.ORANGE_TERRACOTTA, Material.ORANGE_TERRACOTTA, Material.YELLOW_TERRACOTTA,
            Material.TERRACOTTA, Material.WHITE_TERRACOTTA, Material.RED_TERRACOTTA, Material.ORANGE_TERRACOTTA,
            Material.LIGHT_GRAY_TERRACOTTA, Material.BROWN_TERRACOTTA, Material.TERRACOTTA, Material.RED_TERRACOTTA};

    /** A tree to try at this spot, or null. r is uniform 0..1. */
    public TreeType tree(double r) {
        return switch (this) {
            case PLAINS -> r < 0.004 ? TreeType.TREE : null;
            case HILLY_PLAINS -> r < 0.004 ? TreeType.TREE : (r < 0.006 ? TreeType.BIRCH : null);
            case MEADOW -> r < 0.0015 ? TreeType.BIRCH : (r < 0.0025 ? TreeType.TREE : null);
            case OAK_FOREST -> r < 0.035 ? TreeType.TREE : (r < 0.042 ? TreeType.BIG_TREE : (r < 0.05 ? TreeType.BIRCH : null));
            case DARK_OAK_FOREST -> r < 0.045 ? TreeType.DARK_OAK : (r < 0.048 ? TreeType.RED_MUSHROOM
                    : (r < 0.051 ? TreeType.BROWN_MUSHROOM : null));
            case CHERRY_GROVE -> r < 0.045 ? TreeType.CHERRY : null;
            case JUNGLE -> r < 0.012 ? TreeType.JUNGLE : (r < 0.035 ? TreeType.SMALL_JUNGLE : (r < 0.06 ? TreeType.JUNGLE_BUSH : null));
            case SWAMP -> r < 0.012 ? TreeType.SWAMP : null;
            case SNOWY -> r < 0.03 ? TreeType.REDWOOD : (r < 0.04 ? TreeType.TALL_REDWOOD : null);
            case MUSHROOM -> r < 0.012 ? TreeType.RED_MUSHROOM : (r < 0.024 ? TreeType.BROWN_MUSHROOM : null);
            case LUSH_CAVERNS -> r < 0.025 ? TreeType.AZALEA : null;
            case ANCIENT -> r < 0.008 ? TreeType.TALL_BIRCH : (r < 0.012 ? TreeType.BIG_TREE : null);
            default -> null;
        };
    }

    /** A ground plant (block data string) for this spot, or null. Two-block plants use "lower|upper". */
    public String plant(double r) {
        return switch (this) {
            case PLAINS, HILLY_PLAINS -> r < 0.30 ? "minecraft:short_grass" : r < 0.34 ? "minecraft:tall_grass[half=lower]|minecraft:tall_grass[half=upper]"
                    : r < 0.355 ? pick(r, "dandelion", "poppy", "oxeye_daisy", "cornflower", "azure_bluet") : null;
            case MEADOW -> r < 0.35 ? "minecraft:short_grass" : r < 0.45 ? "minecraft:tall_grass[half=lower]|minecraft:tall_grass[half=upper]"
                    : r < 0.58 ? pick(r, "dandelion", "poppy", "allium", "azure_bluet", "oxeye_daisy", "cornflower") : null;
            case OAK_FOREST -> r < 0.22 ? "minecraft:short_grass" : r < 0.24 ? pick(r, "dandelion", "poppy", "lily_of_the_valley")
                    : r < 0.25 ? "minecraft:fern" : null;
            case DARK_OAK_FOREST -> r < 0.12 ? "minecraft:short_grass" : r < 0.14 ? pick(r, "red_mushroom", "brown_mushroom") : null;
            case CHERRY_GROVE -> r < 0.18 ? "minecraft:pink_petals[flower_amount=" + (1 + (int) (r * 400) % 4) + "]"
                    : r < 0.35 ? "minecraft:short_grass" : null;
            case JUNGLE -> r < 0.35 ? "minecraft:short_grass" : r < 0.45 ? "minecraft:fern"
                    : r < 0.46 ? "minecraft:melon" : null;
            case SWAMP -> r < 0.25 ? "minecraft:short_grass" : r < 0.27 ? "minecraft:blue_orchid" : null;
            case DESERT -> r < 0.008 ? "cactus" : r < 0.02 ? "minecraft:dead_bush" : null;
            case BADLANDS -> r < 0.004 ? "cactus" : r < 0.025 ? "minecraft:dead_bush" : null;
            case SNOWY -> r < 0.03 ? "minecraft:sweet_berry_bush[age=3]" : r < 0.9 ? "minecraft:snow[layers=1]" : null;
            case MUSHROOM -> r < 0.05 ? pick(r, "red_mushroom", "brown_mushroom") : null;
            case LUSH_CAVERNS -> r < 0.20 ? "minecraft:moss_carpet" : r < 0.25 ? "minecraft:azalea" : r < 0.29 ? "minecraft:flowering_azalea"
                    : r < 0.31 ? "minecraft:small_dripleaf[half=lower]|minecraft:small_dripleaf[half=upper]" : r < 0.40 ? "minecraft:short_grass" : null;
            case CRYSTAL -> r < 0.012 ? "spire" : r < 0.05 ? "minecraft:amethyst_cluster[facing=up]"
                    : r < 0.09 ? "minecraft:large_amethyst_bud[facing=up]" : r < 0.12 ? "minecraft:medium_amethyst_bud[facing=up]" : null;
            case ANCIENT -> r < 0.010 ? "pillar" : r < 0.025 ? "rubble" : r < 0.25 ? "minecraft:short_grass" : r < 0.30 ? "minecraft:fern" : null;
            case VOLCANIC -> null;
            default -> null;
        };
    }

    private static String pick(double r, String... names) {
        return "minecraft:" + names[(int) (r * 7919) % names.length];
    }

    public static TerrainBiome byId(String id) {
        if (id == null) return null;
        String k = id.toLowerCase(Locale.ROOT);
        for (TerrainBiome b : values()) if (b.id.equals(k)) return b;
        return null;
    }
}
