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
package dev.skyrealms.decay;

import org.bukkit.entity.Player;

/**
 * How much of a player's decay-causing presence actually counts. 1.0 = full effect,
 * 0.0 = none. Cleansing Tonic and Warded gear will plug in here.
 */
@FunctionalInterface
public interface DecayResist {
    DecayResist NONE = p -> 1.0;

    double factor(Player player);
}
