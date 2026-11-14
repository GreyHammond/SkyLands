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
package dev.skyrealms.decay.recycle;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/** plugins/SkyRealms/recycle.yml: which managed overworld is current and when the next recycle is due. */
final class RecycleState {
    private final File file;
    private final Logger log;
    String current;
    int cycle;
    long nextReset;
    /** Every managed world folder that may still exist (including current). Only these are ever deleted. */
    final List<String> managed = new ArrayList<>();

    RecycleState(File file, Logger log) {
        this.file = file;
        this.log = log;
    }

    boolean load() {
        if (!file.exists()) return false;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        current = y.getString("current");
        cycle = y.getInt("cycle", 0);
        nextReset = y.getLong("next-reset", 0);
        managed.clear();
        managed.addAll(y.getStringList("managed"));
        return current != null && cycle > 0;
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of("Managed by SkyLands. Don't edit while the server is running."));
        y.set("current", current);
        y.set("cycle", cycle);
        y.set("next-reset", nextReset);
        y.set("managed", new ArrayList<>(managed));
        try {
            y.save(file);
        } catch (IOException e) {
            log.warning("Could not save " + file.getName() + ": " + e.getMessage());
        }
    }
}
