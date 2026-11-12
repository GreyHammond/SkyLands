package dev.skyrealms.island;

import org.bukkit.Material;

/**
 * Island permissions. ROLE flags are set separately for Visitors and Friends
 * (owners can always do everything). ISLAND flags apply to the whole island.
 */
public enum Flag {
    // ---- per-role ------------------------------------------------------------
    BREAK(Scope.ROLE, Material.IRON_PICKAXE, "Break blocks", "Mine and break blocks", false, true),
    PLACE(Scope.ROLE, Material.BRICKS, "Place blocks", "Place blocks, boats, minecarts, armor stands", false, true),
    HARVEST(Scope.ROLE, Material.WHEAT, "Harvest crops", "Break or pick fully-grown crops, melons, berries", false, true),
    DOORS(Scope.ROLE, Material.OAK_DOOR, "Doors & gates", "Open doors, trapdoors and fence gates", true, true),
    BUTTONS(Scope.ROLE, Material.STONE_BUTTON, "Buttons & levers", "Buttons, levers, pressure plates, tripwires", false, true),
    CONTAINERS(Scope.ROLE, Material.CHEST, "Chests & storage", "Chests, barrels, shulker boxes, hoppers, droppers", false, true),
    FURNACES(Scope.ROLE, Material.FURNACE, "Furnaces", "Furnaces, smokers, blast furnaces", false, true),
    WORKSTATIONS(Scope.ROLE, Material.CRAFTING_TABLE, "Workstations", "Crafting, anvils, enchanting, brewing, looms, etc.", false, true),
    REDSTONE(Scope.ROLE, Material.REPEATER, "Redstone tweaks", "Repeaters, comparators, note blocks, daylight sensors", false, true),
    BEDS(Scope.ROLE, Material.RED_BED, "Beds", "Sleep or set spawn in beds", false, true),
    BUCKETS(Scope.ROLE, Material.WATER_BUCKET, "Buckets", "Fill or empty buckets", false, true),
    TRAMPLE(Scope.ROLE, Material.FARMLAND, "Trample farmland", "Jumping on farmland turns it to dirt", false, false),
    ANIMALS(Scope.ROLE, Material.LEAD, "Animals", "Hurt, breed, shear, milk, leash or name animals", false, true),
    TRADE(Scope.ROLE, Material.EMERALD, "Villager trading", "Trade with villagers", false, true),
    RIDE(Scope.ROLE, Material.SADDLE, "Ride", "Ride horses, boats and minecarts", false, true),
    DECOR(Scope.ROLE, Material.ITEM_FRAME, "Item frames & stands", "Use or break item frames, armor stands, paintings", false, true),
    OTHER(Scope.ROLE, Material.FLOWER_POT, "Other blocks", "Cake, flower pots, composters, signs, jukeboxes, bells, etc.", false, true),

    // ---- whole island -------------------------------------------------------
    VISITS(Scope.ISLAND, Material.OAK_SIGN, "Open to visitors", "Other players can visit your island from the menu", true, true),
    PVP(Scope.ISLAND, Material.IRON_SWORD, "PvP", "Players can fight each other here", false, false),
    EXPLOSIONS(Scope.ISLAND, Material.TNT, "Explosion damage", "Creepers and TNT break blocks", false, false),
    FIRE_SPREAD(Scope.ISLAND, Material.FLINT_AND_STEEL, "Fire spread", "Fire spreads and burns blocks", false, false),
    MOB_GRIEF(Scope.ISLAND, Material.ZOMBIE_HEAD, "Mob griefing", "Mobs trample farmland and break/take blocks", false, false);

    public enum Scope { ROLE, ISLAND }

    public final Scope scope;
    public final Material icon;
    public final String title;
    public final String description;
    public final boolean defaultVisitor;
    public final boolean defaultFriend;

    Flag(Scope scope, Material icon, String title, String description, boolean defaultVisitor, boolean defaultFriend) {
        this.scope = scope;
        this.icon = icon;
        this.title = title;
        this.description = description;
        this.defaultVisitor = defaultVisitor;
        this.defaultFriend = defaultFriend;
    }

    public static Flag parse(String s) {
        try { return valueOf(s.toUpperCase(java.util.Locale.ROOT)); } catch (IllegalArgumentException e) { return null; }
    }
}
