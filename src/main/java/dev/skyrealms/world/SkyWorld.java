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
