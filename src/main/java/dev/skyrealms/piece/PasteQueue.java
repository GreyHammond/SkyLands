package dev.skyrealms.piece;

import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

/** Pastes pieces a slice at a time so even continental islands don't freeze the server. */
public final class PasteQueue extends BukkitRunnable {
    private record Job(World world, Piece piece, int x, int y, int z, int rot, Runnable done) {}

    private final Queue<Job> jobs = new ArrayDeque<>();
    private final int blocksPerTick;
    private Job current;
    private int index;
    private final Set<Long> touchedChunks = new HashSet<>();

    public PasteQueue(Plugin plugin, int blocksPerTick) {
        this.blocksPerTick = Math.max(500, blocksPerTick);
        runTaskTimer(plugin, 1L, 1L);
    }

    /** Queue a piece with its centre at x/y/z. {@code done} runs on the main thread when finished. */
    public void paste(World world, Piece piece, int x, int y, int z, int rot, Runnable done) {
        jobs.add(new Job(world, piece, x, y, z, rot, done));
    }

    public int pending() { return jobs.size() + (current == null ? 0 : 1); }

    @Override
    public void run() {
        int budget = blocksPerTick;
        while (budget > 0) {
            if (current == null) {
                current = jobs.poll();
                index = 0;
                if (current == null) return;
            }
            Job j = current;
            Piece p = j.piece;
            BlockData[] palette = p.palette(j.rot);
            Biome[] biomes = p.biomes();
            int end = Math.min(p.size(), index + budget);
            for (int i = index; i < end; i++) {
                int ox = Piece.rotX(p.dx[i], p.dz[i], j.rot);
                int oz = Piece.rotZ(p.dx[i], p.dz[i], j.rot);
                int bx = j.x + ox, by = j.y + p.dy[i], bz = j.z + oz;
                if (by < j.world.getMinHeight() || by >= j.world.getMaxHeight()) continue;
                j.world.getBlockAt(bx, by, bz).setBlockData(palette[p.block[i]], false);
                Biome b = biomes[p.biome[i]];
                if (b != null) j.world.setBiome(bx, by, bz, b);
                touchedChunks.add(((long) (bx >> 4) << 32) | ((bz >> 4) & 0xffffffffL));
            }
            budget -= end - index;
            index = end;
            if (index >= p.size()) {
                // Resend chunks so players see the new biomes (grass/leaf colours, sky, music).
                for (long key : touchedChunks) {
                    j.world.refreshChunk((int) (key >> 32), (int) key);
                }
                touchedChunks.clear();
                current = null;
                if (j.done != null) j.done.run();
            }
        }
    }
}
