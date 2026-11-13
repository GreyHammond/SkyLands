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

import dev.skyrealms.piece.PasteQueue;
import dev.skyrealms.piece.Piece;
import dev.skyrealms.piece.PieceLibrary;
import dev.skyrealms.terrain.TerrainBiome;
import dev.skyrealms.terrain.TerrainGenerator;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Grows an island outward by attaching new pieces to the edge of the existing land.
 *
 * Rules:
 *  - The first piece sits at the centre.
 *  - Each new piece overlaps an existing one (so the land is continuous) but
 *    not so much that it adds nothing.
 *  - A new biome region attaches on the outer edge (farthest from the centre).
 *  - More pieces of an existing biome cluster around that biome's region.
 *  - Everything stays inside the tier's footprint.
 */
public final class IslandGrower {
    /** Biomes that belong together along a coastline. */
    private static final java.util.Set<String> SHORE = java.util.Set.of("shallow_ocean", "deep_ocean", "beach", "desert", "swamp");

    /** Variant name used for generated (built-in biome) land. */
    public static final String GENERATED = "gen";

    private final PieceLibrary library;
    private final PasteQueue paster;
    private final TerrainGenerator terrain;
    private final int baseY;
    private final int yJitter;
    private final int minRadius, maxRadius;
    private final Random rng = new Random();

    public IslandGrower(PieceLibrary library, PasteQueue paster, TerrainGenerator terrain, int baseY, int yJitter,
                        int minRadius, int maxRadius) {
        this.library = library;
        this.paster = paster;
        this.terrain = terrain;
        this.baseY = baseY;
        this.yJitter = yJitter;
        this.minRadius = Math.max(6, minRadius);
        this.maxRadius = Math.max(this.minRadius, maxRadius);
    }

    /** Attach one piece of land. Returns null if there's no room. */
    public PiecePlacement grow(World world, Island island, String biome, Runnable done) {
        List<PiecePlacement> added = growMany(world, island, biome, 1, done);
        return added.isEmpty() ? null : added.get(0);
    }

    /**
     * Attach up to {@code count} pieces of a biome. Built-in biomes are generated together in one
     * pass so they blend into a single landmass. {@code done} runs once, when all of it is built.
     */
    public List<PiecePlacement> growMany(World world, Island island, String biome, int count, Runnable done) {
        return growMany(world, island, biome, count, null, done);
    }

    /**
     * Like growMany, but each piece attaches as close as it can to {@code target}, an offset
     * {dx, dz} from the island centre (null = automatic placement).
     */
    public List<PiecePlacement> growMany(World world, Island island, String biome, int count, double[] target, Runnable done) {
        List<PiecePlacement> added = new ArrayList<>();
        List<Piece> pastes = new ArrayList<>();
        TerrainBiome tb = TerrainBiome.byId(biome);
        boolean hadLand = !island.pieces.isEmpty();
        for (int n = 0; n < count && island.pieces.size() < island.tier.maxPieces; n++) {
            PiecePlacement p;
            if (tb != null) {
                // Piece size scales with the island, so a Small island still has room for all its pieces.
                int hi = Math.max(6, Math.min(maxRadius, (int) Math.round(island.half() * 0.6)));
                int lo = Math.max(5, Math.min(minRadius, hi - 3));
                int r = lo + rng.nextInt(hi - lo + 1);
                if (island.pieces.isEmpty()) r = Math.min(r, (int) Math.round(island.half() * 0.65));
                p = findSpot(island, biome, GENERATED, target != null ? hi : r, target);
                if (p == null && target == null && r > lo) p = findSpot(island, biome, GENERATED, lo, null); // squeeze a smaller piece in
            } else {
                Piece piece = library.random(biome, rng);
                if (piece == null) break;
                p = findSpot(island, biome, piece.name, piece.radius, target);
                if (p != null) pastes.add(piece);
            }
            if (p == null) break;
            if (tb != null) p = p.withStyle(island.underside);   // new land hangs the island's current underside style
            island.pieces.add(p);
            island.biomes.add(biome);
            added.add(p);
        }
        if (added.isEmpty()) return added;
        if (tb != null) {
            List<TerrainGenerator.Blob> targets = added.stream().map(p -> blob(island, p)).toList();
            terrain.generate(world, seed(island), island.seaY, blobs(island), targets, hadLand, done);
        } else {
            for (int n = 0; n < added.size(); n++) {
                paste(world, island, added.get(n), pastes.get(n), n == added.size() - 1 ? done : null);
            }
        }
        return added;
    }

    /** Re-paste captured pieces and regenerate any missing generated land (fills holes, never overwrites). */
    public int rebuild(World world, Island island) {
        int n = 0;
        List<TerrainGenerator.Blob> gen = new ArrayList<>();
        for (PiecePlacement p : island.pieces) {
            if (GENERATED.equals(p.variant())) { gen.add(blob(island, p)); n++; continue; }
            Piece piece = library.find(p.biome(), p.variant());
            if (piece == null) continue;
            paste(world, island, p, piece, null);
            n++;
        }
        if (!gen.isEmpty()) terrain.generate(world, seed(island), island.seaY, blobs(island), gen, true, null);
        return n;
    }

    private static long seed(Island island) { return island.index * 1_000_003L + 0x51A9L; }

    private TerrainGenerator.Blob blob(Island island, PiecePlacement p) {
        TerrainBiome tb = GENERATED.equals(p.variant()) ? TerrainBiome.byId(p.biome()) : null;
        return new TerrainGenerator.Blob(tb, island.centerX + p.dx(), island.centerZ + p.dz(), p.radius(),
                dev.skyrealms.terrain.UndersideStyle.byId(p.style()));
    }

    private List<TerrainGenerator.Blob> blobs(Island island) {
        return island.pieces.stream().map(p -> blob(island, p)).toList();
    }

    private void paste(World world, Island island, PiecePlacement p, Piece piece, Runnable done) {
        paster.paste(world, piece, island.centerX + p.dx(), island.seaY + p.dy(), island.centerZ + p.dz(), p.rot(), done);
    }

    private PiecePlacement findSpot(Island island, String biome, String variant, int rn, double[] target) {
        boolean gen = GENERATED.equals(variant);
        int rot = gen ? 0 : rng.nextInt(4);
        int dy = gen || yJitter <= 0 ? 0 : rng.nextInt(yJitter * 2 + 1) - yJitter;
        if (island.pieces.isEmpty()) {
            return new PiecePlacement(biome, variant, 0, 0, 0, rot, rn);
        }

        if (target != null) return towardTarget(island, biome, variant, rn, rot, dy, target);

        List<PiecePlacement> sameBiome = island.pieces.stream().filter(p -> p.biome().equals(biome)).toList();
        boolean newRegion = sameBiome.isEmpty();

        if (newRegion && SHORE.contains(biome)) {
            // A first ocean or beach attaches next to existing water, beach, desert or swamp if there is any.
            List<PiecePlacement> shore = island.pieces.stream().filter(p -> SHORE.contains(p.biome())).toList();
            if (!shore.isEmpty()) {
                PiecePlacement near = bestCandidate(island, shore, biome, variant, rn, rot, dy, false);
                if (near != null) return near;
            }
        }
        PiecePlacement spot = bestCandidate(island, newRegion ? island.pieces : sameBiome, biome, variant, rn, rot, dy, newRegion);
        if (spot == null && !newRegion) {
            // That biome's region is boxed in; let it spill out from any edge instead.
            spot = bestCandidate(island, island.pieces, biome, variant, rn, rot, dy, true);
        }
        return spot;
    }

    /**
     * Placement the player asked for. The land must attach on the chosen side: within about 50
     * degrees of the direction from the island centre to {@code target}, as close to the target as
     * possible. If a normal-size piece won't fit there, smaller ones are tried. If nothing fits on
     * that side it returns null; it never puts the land somewhere else.
     */
    private PiecePlacement towardTarget(Island island, String biome, String variant, int maxR, int rot, int dy, double[] target) {
        int half = island.half();
        double tLen = Math.hypot(target[0], target[1]);
        boolean sector = tLen > half * 0.2;
        double tAngle = Math.atan2(target[1], target[0]);
        for (int rn = maxR; rn >= 5; rn -= 2) {
            double[] best = null;
            double bestDist = Double.MAX_VALUE;
            for (int attempt = 0; attempt < 900; attempt++) {
                PiecePlacement a = island.pieces.get(rng.nextInt(island.pieces.size()));
                double angle = rng.nextDouble() * Math.PI * 2;
                double dist = (a.radius() + rn) * (0.45 + rng.nextDouble() * 0.40);
                double x = a.dx() + Math.cos(angle) * dist;
                double z = a.dz() + Math.sin(angle) * dist;
                if (Math.abs(x) > half - rn * 0.2 || Math.abs(z) > half - rn * 0.2) continue;   // centre inside the border
                if (sector) {
                    double diff = Math.abs(Math.atan2(Math.sin(Math.atan2(z, x) - tAngle), Math.cos(Math.atan2(z, x) - tAngle)));
                    if (diff > Math.toRadians(50)) continue;                                   // must be on the chosen side
                }
                if (newGround(island, x, z, rn) < 0.30) continue;                              // must add real new land
                double d = Math.hypot(x - target[0], z - target[1]);
                if (d < bestDist) { bestDist = d; best = new double[]{x, z}; }
            }
            if (best != null) return new PiecePlacement(biome, variant, (int) Math.round(best[0]), dy, (int) Math.round(best[1]), rot, rn);
        }
        return null;
    }

    /** Fraction of a new piece's area not already covered by existing pieces (sampled). */
    private static double newGround(Island island, double x, double z, int rn) {
        int total = 0, fresh = 0;
        for (int ring = 0; ring <= 3; ring++) {
            double rr = rn * ring / 3.5;
            int pts = ring == 0 ? 1 : ring * 8;
            for (int k = 0; k < pts; k++) {
                double ang = Math.PI * 2 * k / pts;
                double px = x + Math.cos(ang) * rr, pz = z + Math.sin(ang) * rr;
                total++;
                boolean covered = false;
                for (PiecePlacement o : island.pieces) {
                    if (Math.hypot(o.dx() - px, o.dz() - pz) < o.radius() * 0.9) { covered = true; break; }
                }
                if (!covered) fresh++;
            }
        }
        return fresh / (double) total;
    }

    private PiecePlacement bestCandidate(Island island, List<PiecePlacement> anchors, String biome,
                                         String variant, int rn, int rot, int dy, boolean preferOutward) {
        int half = island.half();
        // Allow a piece to poke slightly past the border: spheres are round, the border is square.
        double edgeAllowance = rn * 0.8;

        double cx = 0, cz = 0;
        for (PiecePlacement a : anchors) { cx += a.dx(); cz += a.dz(); }
        cx /= anchors.size(); cz /= anchors.size();

        List<double[]> valid = new ArrayList<>();
        for (int attempt = 0; attempt < 120; attempt++) {
            PiecePlacement a = anchors.get(rng.nextInt(anchors.size()));
            double angle = rng.nextDouble() * Math.PI * 2;
            double dist = (a.radius() + rn) * (0.55 + rng.nextDouble() * 0.25);
            double x = a.dx() + Math.cos(angle) * dist;
            double z = a.dz() + Math.sin(angle) * dist;
            if (Math.abs(x) + edgeAllowance > half || Math.abs(z) + edgeAllowance > half) continue;
            boolean ok = true;
            for (PiecePlacement o : island.pieces) {
                double d = Math.hypot(o.dx() - x, o.dz() - z);
                if (d < (o.radius() + rn) * 0.5) { ok = false; break; }
            }
            if (!ok) continue;
            double score = preferOutward
                    ? Math.hypot(x, z)                       // push new regions to the rim
                    : -Math.hypot(x - cx, z - cz);           // keep a biome's region compact
            valid.add(new double[]{x, z, score + rng.nextDouble() * rn}); // a little randomness
        }
        if (valid.isEmpty()) return null;
        double[] best = valid.stream().max((p, q) -> Double.compare(p[2], q[2])).get();
        return new PiecePlacement(biome, variant, (int) Math.round(best[0]), dy, (int) Math.round(best[1]), rot, rn);
    }
}
