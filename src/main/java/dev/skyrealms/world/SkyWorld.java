package dev.skyrealms.world;

import org.bukkit.*;

/** Loads (or creates) the void world islands live in. Works with or without Multiverse. */
public final class SkyWorld {
    private SkyWorld() {}

    public static World loadOrCreate(String name) {
        World w = Bukkit.getWorld(name);
        if (w != null) return w;
        w = new WorldCreator(name)
                .environment(World.Environment.NORMAL)
                .generator(new VoidGenerator())
                .generateStructures(false)
                .createWorld();
        if (w != null) {
            w.setSpawnLocation(0, 200, 0);
            w.setGameRule(GameRules.SPAWN_MONSTERS, true);
        }
        return w;
    }
}
