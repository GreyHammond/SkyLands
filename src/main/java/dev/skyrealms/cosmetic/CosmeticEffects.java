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
package dev.skyrealms.cosmetic;

import dev.skyrealms.SkyRealmsPlugin;
import dev.skyrealms.island.Island;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Ambient island particles and border lights. Everything is sent only to the players who are
 * on that island (player.spawnParticle), so it costs nothing for anyone else.
 */
public final class CosmeticEffects implements Runnable {
    private static final Particle.DustOptions BASIC = new Particle.DustOptions(Color.fromRGB(240, 245, 255), 1.1f);
    private static final Color[] AURORA = {Color.fromRGB(80, 255, 170), Color.fromRGB(120, 200, 255), Color.fromRGB(190, 120, 255)};

    private final SkyRealmsPlugin plugin;
    private int tick;

    public CosmeticEffects(SkyRealmsPlugin plugin) { this.plugin = plugin; }

    @Override
    public void run() {
        tick++;
        for (Player p : plugin.skyWorld().getPlayers()) {
            Island i = plugin.islands().at(p.getX(), p.getZ());
            if (i == null) continue;
            ambient(p, Cosmetics.ParticleSet.byId(i.particles));
            border(p, i, Cosmetics.BorderStyle.byId(i.border));
        }
    }

    private void ambient(Player p, Cosmetics.ParticleSet set) {
        if (set == Cosmetics.ParticleSet.NONE) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Location c = p.getLocation();
        for (int n = 0; n < 6; n++) {
            Location at = c.clone().add(r.nextDouble(-12, 12), r.nextDouble(-2, 8), r.nextDouble(-12, 12));
            switch (set) {
                case FIREFLIES -> p.spawnParticle(Particle.FIREFLY, at, 1, 0, 0, 0, 0);
                case PETALS -> p.spawnParticle(Particle.CHERRY_LEAVES, at.add(0, 4, 0), 1, 0, 0, 0, 0);
                case SNOWFALL -> p.spawnParticle(Particle.SNOWFLAKE, at.add(0, 4, 0), 1, 0.2, 0, 0.2, 0.01);
                case EMBERS -> {
                    if (n % 2 == 0) p.spawnParticle(Particle.SMALL_FLAME, at, 1, 0, 0.1, 0, 0.005);
                    else p.spawnParticle(Particle.WHITE_ASH, at, 2, 0.5, 0.5, 0.5, 0);
                }
                case SPARKLES -> p.spawnParticle(n % 2 == 0 ? Particle.ELECTRIC_SPARK : Particle.END_ROD, at, 1, 0, 0, 0, 0.01);
                case AURORA -> {
                    Location sky = c.clone().add(r.nextDouble(-20, 20), 18 + r.nextDouble(0, 6), r.nextDouble(-20, 20));
                    Color col = AURORA[(tick / 4 + n) % AURORA.length];
                    p.spawnParticle(Particle.DUST, sky, 3, 1.5, 0.3, 1.5, 0, new Particle.DustOptions(col, 2.2f));
                }
                default -> { }
            }
        }
    }

    /** Light up the stretch of border near the player, if they're within 10 blocks of it. */
    private void border(Player p, Island i, Cosmetics.BorderStyle style) {
        if (style == Cosmetics.BorderStyle.NONE) return;
        int half = i.half();
        double minX = i.centerX - half, maxX = i.centerX + half + 1, minZ = i.centerZ - half, maxZ = i.centerZ + half + 1;
        double px = p.getX(), pz = p.getZ(), py = p.getY();
        double reach = 10;
        if (px - minX > reach && maxX - px > reach && pz - minZ > reach && maxZ - pz > reach) return;
        for (double t = -reach; t <= reach; t += 1.5) {
            for (double y = py - 1; y <= py + 2.5; y += 1.2) {
                if (Math.abs(px - minX) <= reach) show(p, style, minX, y, clamp(pz + t, minZ, maxZ));
                if (Math.abs(maxX - px) <= reach) show(p, style, maxX, y, clamp(pz + t, minZ, maxZ));
                if (Math.abs(pz - minZ) <= reach) show(p, style, clamp(px + t, minX, maxX), y, minZ);
                if (Math.abs(maxZ - pz) <= reach) show(p, style, clamp(px + t, minX, maxX), y, maxZ);
            }
        }
    }

    private void show(Player p, Cosmetics.BorderStyle style, double x, double y, double z) {
        Location at = new Location(p.getWorld(), x, y, z);
        switch (style) {
            case BASIC -> p.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, BASIC);
            case LEAVES -> p.spawnParticle(Particle.HAPPY_VILLAGER, at, 1, 0, 0, 0, 0);
            case GLOW -> p.spawnParticle(Particle.GLOW, at, 1, 0, 0, 0, 0);
            case FLAME -> p.spawnParticle(Particle.SMALL_FLAME, at, 1, 0, 0, 0, 0);
            default -> { }
        }
    }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
}
