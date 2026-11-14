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
package dev.skyrealms.economy;

import java.util.List;

/** The custom recipes, written out for the Recipes page (chest menu and Bedrock form). */
public final class Recipes {
    private Recipes() {}

    public static final List<String> WARD = List.of(
            "Crafting: 8 Void Shards around Crying Obsidian = Ward Template",
            "Smithing: Ward Template + armor + Void Shard = Ward I",
            "Crafting: Ward Template surrounded by 8 Corrupted Remnants = Corrupted Template",
            "Smithing: Corrupted Template + Ward I armor + Corrupted Remnant = Ward II",
            "Smithing: Corrupted Template + Ward II armor + Echo Shard = Ward III");

    public static final List<String> TONIC = List.of(
            "Water Bottle + Void Shard = Tainted Potion",
            "Tainted Potion + Corrupted Spider Eye = Cleansing Tonic I",
            "Tonic I + Corrupted Bone = Cleansing Tonic II",
            "Tonic II + Corrupted Remnant = Cleansing Tonic III",
            "Any tonic + Corrupted Gunpowder = Splash tonic (protects players nearby)");

    public static final List<String> SOURCES = List.of(
            "Void Shards: mining corrupted land, corrupted mobs",
            "Corrupted Remnants: corrupted mobs",
            "Corrupted Spider Eye / Bone / Gunpowder: corrupted spiders / skeletons / creepers",
            "Echo Shards: chests in Ancient Cities (Deep Dark)");
}
