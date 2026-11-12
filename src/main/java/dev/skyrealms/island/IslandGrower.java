package dev.skyrealms.island;

import dev.skyrealms.piece.PasteQueue;
import dev.skyrealms.piece.Piece;
import dev.skyrealms.piece.PieceLibrary;
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
    private final PieceLibrary library;
    private final PasteQueue paster;
    private final int baseY;
    private final int yJitter;
    private final Random rng = new Random();

    public IslandGrower(PieceLibrary library, PasteQueue paster, int baseY, int yJitter) {
        this.library = library;
        this.paster = paster;
        this.baseY = baseY;
        this.yJitter = yJitter;
    }

    /** Find a spot, record it on the island, and queue the paste. Returns null if there's no room. */
    public PiecePlacement grow(World world, Island island, String biome, Runnable done) {
        if (island.pieces.size() >= island.tier.maxPieces) return null;
        Piece piece = library.random(biome, rng);
        if (piece == null) return null;
        PiecePlacement placement = findSpot(island, biome, piece);
        if (placement == null) return null;
        island.pieces.add(placement);
        island.biomes.add(biome);
        paste(world, island, placement, piece, done);
        return placement;
    }

    /** Re-paste every piece (admin repair command). */
    public int rebuild(World world, Island island) {
        int n = 0;
        for (PiecePlacement p : island.pieces) {
            Piece piece = library.find(p.biome(), p.variant());
            if (piece == null) continue;
            paste(world, island, p, piece, null);
            n++;
        }
        return n;
    }

    private void paste(World world, Island island, PiecePlacement p, Piece piece, Runnable done) {
        paster.paste(world, piece, island.centerX + p.dx(), baseY + p.dy(), island.centerZ + p.dz(), p.rot(), done);
    }

    private PiecePlacement findSpot(Island island, String biome, Piece piece) {
        int rot = rng.nextInt(4);
        int dy = yJitter > 0 ? rng.nextInt(yJitter * 2 + 1) - yJitter : 0;
        int rn = piece.radius;
        if (island.pieces.isEmpty()) {
            return new PiecePlacement(biome, piece.name, 0, 0, 0, rot, rn);
        }

        List<PiecePlacement> sameBiome = island.pieces.stream().filter(p -> p.biome().equals(biome)).toList();
        boolean newRegion = sameBiome.isEmpty();

        PiecePlacement spot = bestCandidate(island, newRegion ? island.pieces : sameBiome, biome, piece, rot, dy, newRegion);
        if (spot == null && !newRegion) {
            // That biome's region is boxed in; let it spill out from any edge instead.
            spot = bestCandidate(island, island.pieces, biome, piece, rot, dy, true);
        }
        return spot;
    }

    private PiecePlacement bestCandidate(Island island, List<PiecePlacement> anchors, String biome,
                                         Piece piece, int rot, int dy, boolean preferOutward) {
        int rn = piece.radius;
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
        return new PiecePlacement(biome, piece.name, (int) Math.round(best[0]), dy, (int) Math.round(best[1]), rot, rn);
    }
}
