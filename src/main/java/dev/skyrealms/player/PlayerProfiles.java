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
package dev.skyrealms.player;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;

/**
 * Everything the plugin remembers about a player that isn't part of an island: first-join state,
 * the free first island, the tutorial and the journal. Saved to plugins/SkyRealms/profiles.yml.
 */
public final class PlayerProfiles {
    /** One player's record. Change fields freely, then call {@link PlayerProfiles#dirty()}. */
    public static final class Profile {
        /** The first-join welcome (hotbar, Guide, island claim) has run. */
        public boolean seenFirstJoin;
        /** The free first Small island has been claimed. */
        public boolean freeIslandUsed;
        /** Tutorial: index of the current step (0 = first step). Past the last step = finished. */
        public int tutorialStep;
        public boolean tutorialSkipped;
        /** Journal page ids the player has unlocked. */
        public final Set<String> pages = new LinkedHashSet<>();
        /** Tutorial steps already paid out, so a restart never pays twice. */
        public final Set<Integer> rewarded = new HashSet<>();
        /** Free-form counters (e.g. shards collected toward a step). */
        public final Map<String, Integer> counters = new HashMap<>();

        public int counter(String key) { return counters.getOrDefault(key, 0); }
        public int addCounter(String key, int amount) { return counters.merge(key, amount, Integer::sum); }
    }

    private final File file;
    private final Logger log;
    private final Map<UUID, Profile> profiles = new HashMap<>();
    private boolean dirty;

    public PlayerProfiles(File file, Logger log) {
        this.file = file;
        this.log = log;
        load();
    }

    public Profile get(UUID id) { return profiles.computeIfAbsent(id, k -> new Profile()); }

    public boolean known(UUID id) { return profiles.containsKey(id); }

    public void dirty() { dirty = true; }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        for (String key : yml.getKeys(false)) {
            ConfigurationSection s = yml.getConfigurationSection(key);
            if (s == null) continue;
            UUID id;
            try { id = UUID.fromString(key); } catch (IllegalArgumentException e) { continue; }
            Profile p = new Profile();
            p.seenFirstJoin = s.getBoolean("seen-first-join");
            p.freeIslandUsed = s.getBoolean("free-island-used");
            p.tutorialStep = s.getInt("tutorial-step");
            p.tutorialSkipped = s.getBoolean("tutorial-skipped");
            p.pages.addAll(s.getStringList("pages"));
            p.rewarded.addAll(s.getIntegerList("rewarded"));
            ConfigurationSection c = s.getConfigurationSection("counters");
            if (c != null) for (String k : c.getKeys(false)) p.counters.put(k, c.getInt(k));
            profiles.put(id, p);
        }
    }

    /** Writes the file if anything changed. Cheap to call often. */
    public void save() {
        if (!dirty) return;
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, Profile> e : profiles.entrySet()) {
            Profile p = e.getValue();
            ConfigurationSection s = yml.createSection(e.getKey().toString());
            s.set("seen-first-join", p.seenFirstJoin);
            s.set("free-island-used", p.freeIslandUsed);
            s.set("tutorial-step", p.tutorialStep);
            s.set("tutorial-skipped", p.tutorialSkipped);
            s.set("pages", new ArrayList<>(p.pages));
            s.set("rewarded", new ArrayList<>(p.rewarded));
            if (!p.counters.isEmpty()) s.createSection("counters", p.counters);
        }
        try { yml.save(file); dirty = false; } catch (IOException ex) { log.severe("Could not save profiles.yml: " + ex.getMessage()); }
    }
}
