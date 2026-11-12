package dev.skyrealms.island;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.CaveVinesPlant;
import org.bukkit.entity.*;

import java.util.EnumSet;
import java.util.Set;

/** Decides which {@link Flag} an action needs. Kept free of events so it's easy to test. */
public final class BlockRules {
    private BlockRules() {}

    private static final Set<Material> FURNACES = EnumSet.of(Material.FURNACE, Material.SMOKER, Material.BLAST_FURNACE);
    private static final Set<Material> WORKSTATIONS = EnumSet.of(
            Material.CRAFTING_TABLE, Material.ENCHANTING_TABLE, Material.GRINDSTONE, Material.LOOM,
            Material.CARTOGRAPHY_TABLE, Material.SMITHING_TABLE, Material.STONECUTTER, Material.BREWING_STAND,
            Material.BEACON, Material.ANVIL, Material.CHIPPED_ANVIL, Material.DAMAGED_ANVIL);
    private static final Set<Material> REDSTONE = EnumSet.of(
            Material.REPEATER, Material.COMPARATOR, Material.DAYLIGHT_DETECTOR, Material.NOTE_BLOCK);
    private static final Set<Material> EXTRA_STORAGE = EnumSet.of(
            Material.CHISELED_BOOKSHELF, Material.DECORATED_POT, Material.LECTERN, Material.JUKEBOX);
    /** Always allowed: personal or harmless. */
    private static final Set<Material> ALWAYS = EnumSet.of(Material.ENDER_CHEST);
    private static final Set<Material> HARVESTABLE = EnumSet.of(
            Material.MELON, Material.PUMPKIN, Material.SUGAR_CANE, Material.BAMBOO, Material.CACTUS,
            Material.KELP, Material.KELP_PLANT, Material.COCOA);

    /** Right-clicking a block with an empty hand / non-placing item. Returns null if no check is needed. */
    @SuppressWarnings("deprecation")
    public static Flag forInteract(Block block) {
        Material m = block.getType();
        if (ALWAYS.contains(m)) return null;
        if (Tag.DOORS.isTagged(m) || Tag.TRAPDOORS.isTagged(m) || Tag.FENCE_GATES.isTagged(m)) return Flag.DOORS;
        if (Tag.BUTTONS.isTagged(m) || m == Material.LEVER) return Flag.BUTTONS;
        if (FURNACES.contains(m)) return Flag.FURNACES;
        if (WORKSTATIONS.contains(m)) return Flag.WORKSTATIONS;
        if (REDSTONE.contains(m)) return Flag.REDSTONE;
        if (Tag.BEDS.isTagged(m) || m == Material.RESPAWN_ANCHOR) return Flag.BEDS;
        if (m == Material.SWEET_BERRY_BUSH || m == Material.CAVE_VINES || m == Material.CAVE_VINES_PLANT) return Flag.HARVEST;
        if (EXTRA_STORAGE.contains(m) || block.getState(false) instanceof Container) return Flag.CONTAINERS;
        if (m.isInteractable()) return Flag.OTHER;
        return null;
    }

    /** Right-clicking a block while holding an item that changes the block (strip, till, bone meal...). */
    public static boolean modifiesBlock(Material item) {
        String n = item.name();
        return n.endsWith("_AXE") || n.endsWith("_HOE") || n.endsWith("_SHOVEL") || n.endsWith("_SPAWN_EGG")
                || item == Material.FLINT_AND_STEEL || item == Material.FIRE_CHARGE || item == Material.BONE_MEAL
                || item == Material.HONEYCOMB || item == Material.BRUSH || item == Material.SHEARS
                || item == Material.GLASS_BOTTLE || item == Material.WIND_CHARGE;
    }

    /** Breaking a block: grown crops count as harvesting, everything else as breaking. */
    public static Flag forBreak(Block block) {
        BlockData d = block.getBlockData();
        Material m = block.getType();
        if (HARVESTABLE.contains(m)) return Flag.HARVEST;
        if (isCrop(m) && d instanceof Ageable a) return a.getAge() >= a.getMaximumAge() ? Flag.HARVEST : Flag.BREAK;
        if (d instanceof CaveVinesPlant v && v.hasBerries()) return Flag.HARVEST;
        return Flag.BREAK;
    }

    /** Placing a block: replanting seeds counts as harvesting so farm helpers don't need full build rights. */
    public static Flag forPlace(Block placed) {
        return isCrop(placed.getType()) ? Flag.HARVEST : Flag.PLACE;
    }

    /** Stepping on a block. Returns null if no check is needed. */
    public static Flag forPhysical(Material m) {
        if (m == Material.FARMLAND || m == Material.TURTLE_EGG || m == Material.SNIFFER_EGG) return Flag.TRAMPLE;
        if (Tag.PRESSURE_PLATES.isTagged(m) || m == Material.TRIPWIRE) return Flag.BUTTONS;
        return null;
    }

    public static boolean isCrop(Material m) {
        return Tag.CROPS.isTagged(m) || m == Material.NETHER_WART || m == Material.SWEET_BERRY_BUSH
                || m == Material.COCOA || m == Material.TORCHFLOWER_CROP || m == Material.PITCHER_CROP;
    }

    /** Right-clicking an entity. Returns null if no check is needed. */
    public static Flag forEntityInteract(Entity e) {
        if (e instanceof Player) return null;
        if (e instanceof AbstractVillager) return Flag.TRADE;
        if (e instanceof Hanging || e instanceof ArmorStand) return Flag.DECOR;
        if (e instanceof org.bukkit.inventory.InventoryHolder && e instanceof Vehicle && !(e instanceof LivingEntity)) return Flag.CONTAINERS; // chest boats/minecarts
        if (e instanceof Vehicle && !(e instanceof LivingEntity)) return Flag.RIDE;      // boats, minecarts
        if (e instanceof Monster) return null;
        if (e instanceof LivingEntity) return Flag.ANIMALS;                               // breed, shear, leash, ride-able mobs
        return null;
    }

    /** Hitting an entity. PvP is handled separately. Returns null if no check is needed. */
    public static Flag forEntityDamage(Entity e) {
        if (e instanceof Player || e instanceof Monster || e instanceof Slime || e instanceof Phantom
                || e instanceof EnderDragon || e instanceof Shulker || e instanceof Ghast) return null;
        if (e instanceof Hanging || e instanceof ArmorStand) return Flag.DECOR;
        if (e instanceof Vehicle && !(e instanceof LivingEntity)) return Flag.BREAK;
        if (e instanceof LivingEntity) return Flag.ANIMALS;
        return null;
    }

}
