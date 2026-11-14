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

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

/** Everything tunable about decay, read once from the {@code decay:} section of config.yml. */
public final class DecaySettings {
    public final boolean enabled, allowMainWorld, ignoreCreative, particles, actionBar;
    public final String world;
    /** Decay added per second to the chunk an active player stands in. */
    public final double gainPerSecond;
    /** Extra gain multiplier at 100 decay: gain = base * (1 + ramp * value/100). */
    public final double ramp;
    /** Share of the player's gain that spills into the 8 surrounding chunks. */
    public final double neighborFraction, neighborCap;
    /** Decay lost per second by a chunk with nobody near it. */
    public final double healPerSecond;
    public final double leakThreshold, leakPerSecond, leakCap;
    public final double stage1, stage2, stage3;
    /** Block-change attempts per second in a chunk at 100 decay (scales linearly with decay). */
    public final double attemptsPerSecondMax;
    public final int maxAttemptsPerTick, bedrockDepth, safeRadius, afkSeconds, subsurfaceDepth;
    /** Block changes speed up by this factor once a chunk reaches stage 3. */
    public final double haste;
    public final double minMove;
    /** Chunks around the player corrupted at full strength (0 = only their own chunk). */
    public final int radius;
    /** Extra rings of chunks beyond the radius that get a fading share. */
    public final int falloff;

    private DecaySettings(ConfigurationSection c, String defaultWorld) {
        enabled = c.getBoolean("enabled", false);
        allowMainWorld = c.getBoolean("allow-main-world", false);
        world = c.getString("world", defaultWorld);
        ignoreCreative = c.getBoolean("ignore-creative", true);
        particles = c.getBoolean("particles", true);
        actionBar = c.getBoolean("action-bar", true);
        gainPerSecond = Math.max(0, c.getDouble("gain-per-second", 0.03));
        ramp = Math.max(0, c.getDouble("ramp", 2.0));
        neighborFraction = clamp(c.getDouble("neighbor-fraction", 0.5), 0, 1);
        neighborCap = clamp(c.getDouble("neighbor-cap", 50), 0, 100);
        healPerSecond = Math.max(0, c.getDouble("heal-per-second", 0.01));
        leakThreshold = clamp(c.getDouble("leak-threshold", 75), 0, 100);
        leakPerSecond = Math.max(0, c.getDouble("leak-per-second", 0.02));
        leakCap = clamp(c.getDouble("leak-cap", 60), 0, 100);
        stage1 = clamp(c.getDouble("stages.decayed", 25), 1, 100);
        stage2 = clamp(c.getDouble("stages.blighted", 50), stage1, 100);
        stage3 = clamp(c.getDouble("stages.barren", 75), stage2, 100);
        attemptsPerSecondMax = Math.max(0, c.getDouble("attempts-per-second-max", 20));
        maxAttemptsPerTick = Math.max(1, c.getInt("max-attempts-per-tick", 200));
        bedrockDepth = Math.max(1, c.getInt("bedrock-depth", 24));
        safeRadius = Math.max(1, c.getInt("safe-radius", 3));
        afkSeconds = Math.max(5, c.getInt("afk-seconds", 60));
        subsurfaceDepth = Math.max(1, c.getInt("subsurface-depth", 6));
        haste = Math.max(1, c.getDouble("haste", 3.0));
        radius = Math.max(0, Math.min(6, c.getInt("radius", 2)));
        falloff = Math.max(1, Math.min(6, c.getInt("falloff", 2)));
        minMove = Math.max(0.1, c.getDouble("min-move", 0.5));
    }

    public static DecaySettings load(ConfigurationSection section, String defaultWorld) {
        return new DecaySettings(section != null ? section : new MemoryConfiguration(), defaultWorld);
    }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
}
