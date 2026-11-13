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
package dev.skyrealms.terrain;

/** Small seeded 2D value noise with fractal octaves. Deterministic and thread-safe. */
public final class Noise {
    private final long seed;

    public Noise(long seed) { this.seed = seed * 0x9E3779B97F4A7C15L + 0x632BE59BD9B4E019L; }

    private double hash(int x, int z) {
        long h = seed ^ (x * 0x27D4EB2F165667C5L) ^ (z * 0x165667B19E3779F9L);
        h ^= h >>> 31; h *= 0x7FB5D329728EA185L; h ^= h >>> 27; h *= 0x81DADEF4BC2DD44DL; h ^= h >>> 33;
        return ((h >>> 11) * 0x1.0p-53) * 2 - 1; // [-1, 1)
    }

    private static double fade(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }

    /** Single octave, roughly [-1, 1]. */
    public double value(double x, double z) {
        int x0 = (int) Math.floor(x), z0 = (int) Math.floor(z);
        double fx = fade(x - x0), fz = fade(z - z0);
        double a = hash(x0, z0), b = hash(x0 + 1, z0), c = hash(x0, z0 + 1), d = hash(x0 + 1, z0 + 1);
        double ab = a + (b - a) * fx, cd = c + (d - c) * fx;
        return ab + (cd - ab) * fz;
    }

    /** Fractal noise, roughly [-1, 1]. */
    public double fbm(double x, double z, int octaves) {
        double sum = 0, amp = 1, norm = 0, f = 1;
        for (int i = 0; i < octaves; i++) {
            sum += value(x * f + i * 31.7, z * f - i * 17.3) * amp;
            norm += amp; amp *= 0.5; f *= 2;
        }
        return sum / norm;
    }

    /** Deterministic 0..1 random for a position + salt (for scattering plants and ores). */
    public double random(int x, int y, int z, int salt) {
        return (hash(x * 73856093 ^ salt, z * 19349663 ^ y * 83492791) + 1) * 0.5;
    }
}
