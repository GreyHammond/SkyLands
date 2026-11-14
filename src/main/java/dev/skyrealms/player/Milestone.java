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

/**
 * Things a player does that the tutorial and journal care about. Features report them with
 * {@code plugin.milestones().trigger(player, milestone, amount)}; they never need to know who listens.
 */
public enum Milestone {
    CLAIMED_ISLAND,
    LANDED_OVERWORLD,
    RETURNED_HOME,
    /** Standing in a chunk at stage 1 (25%) corruption or more. Fires about once a second while it's true. */
    ENTERED_CORRUPTION,
    /** amount = shards picked up. */
    COLLECTED_SHARD,
    KILLED_CORRUPTED_MOB,
    SOLD_AT_EXCHANGE,
    COLLECTED_CORRUPTED_SPIDER_EYE,
    /** Took a Cleansing Tonic I out of a brewing stand. */
    BREWED_TONIC,
    CRAFTED_WARD_TEMPLATE,
    SMITHED_WARD,
    CLAIMED_CONTRACT
}
