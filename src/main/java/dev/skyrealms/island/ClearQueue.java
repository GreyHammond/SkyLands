package dev.skyrealms.island;

import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Wipes a deleted island chunk by chunk. A chunk snapshot finds the non-air blocks
 * quickly (the sky world is mostly void), and only those get set to air, a slice per tick.
 */
public final class ClearQueue extends BukkitRunnable {
    private record Work(World world, int cx, int cz) {}

    private final Queue<Work> chunks = new ArrayDeque<>();
    private final int blocksPerTick;

    public ClearQueue(Plugin plugin, int blocksPerTick) {
        this.blocksPerTick = Math.max(500, blocksPerTick);
        runTaskTimer(plugin, 2L, 1L);
    }

    /** Queue every chunk touching the square centred on (x, z) with the given half-width. */
    public void clear(World world, int x, int z, int half) {
        for (int cx = (x - half) >> 4; cx <= (x + half) >> 4; cx++)
            for (int cz = (z - half) >> 4; cz <= (z + half) >> 4; cz++)
                chunks.add(new Work(world, cx, cz));
    }

    public int pending() { return chunks.size(); }

    @Override
    public void run() {
        int budget = blocksPerTick;
        while (budget > 0 && !chunks.isEmpty()) {
            Work w = chunks.poll();
            Chunk chunk = w.world.getChunkAt(w.cx, w.cz);
            for (Entity e : chunk.getEntities()) if (!(e instanceof Player)) e.remove();
            ChunkSnapshot snap = chunk.getChunkSnapshot(true, false, false);
            int min = w.world.getMinHeight();
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    // The height lookup skips plants (grass, flowers, sugar cane), so scan a little above it.
                    int top = Math.min(w.world.getMaxHeight() - 1, snap.getHighestBlockYAt(x, z) + 8);
                    for (int y = top; y >= min; y--) {
                        if (snap.getBlockType(x, y, z).isAir()) continue;
                        chunk.getBlock(x, y, z).setType(Material.AIR, false);
                        budget--;
                    }
                }
            }
            budget -= 256; // cost of scanning the chunk itself
        }
    }
}
