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
package dev.skyrealms.island;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;

/**
 * A player's island progress, kept when they delete their island: XP (and so level and its
 * unlocks), unlocked biomes, underside style and cosmetics. Their next island starts with it,
 * so players can delete a starter island and rebuild it properly without losing anything.
 */
public final class ProgressStore {
    private final File file;
    private final Logger log;
    private final YamlConfiguration yml;

    public ProgressStore(File file, Logger log) {
        this.file = file;
        this.log = log;
        this.yml = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
    }

    /** Remember an island's progress for its owner (called before it's deleted). */
    public void save(UUID owner, Island i) {
        ConfigurationSection s = yml.createSection(owner.toString());
        s.set("xp", i.xp);
        s.set("biomes", new ArrayList<>(i.biomes));
        s.set("underside", i.underside);
        s.set("particles", i.particles);
        s.set("border", i.border);
        s.set("theme", i.theme);
        s.set("name", i.name);
        s.set("saved-at", System.currentTimeMillis());
        flush();
    }

    public boolean has(UUID owner) { return yml.isConfigurationSection(owner.toString()); }

    public long xp(UUID owner) { return has(owner) ? yml.getLong(owner + ".xp", 0) : 0; }

    /** Restore saved progress onto a newly bought island. Returns false if there was nothing saved. */
    public boolean apply(UUID owner, Island i) {
        ConfigurationSection s = yml.getConfigurationSection(owner.toString());
        if (s == null) return false;
        i.xp = Math.max(i.xp, s.getLong("xp", 0));
        i.xpBackfilled = true;
        i.biomes.addAll(s.getStringList("biomes"));
        i.underside = s.getString("underside", i.underside);
        i.particles = s.getString("particles", i.particles);
        i.border = s.getString("border", i.border);
        i.theme = s.getString("theme");
        if (i.name == null) i.name = s.getString("name");
        return true;
    }

    private void flush() {
        try { yml.save(file); } catch (IOException e) { log.severe("Could not save players.yml: " + e.getMessage()); }
    }
}
