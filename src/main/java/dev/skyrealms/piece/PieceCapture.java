package dev.skyrealms.piece;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Grabs a sphere of real terrain around a point and turns it into a {@link Piece}. */
public final class PieceCapture {
    private PieceCapture() {}

    /**
     * @param center sphere centre (usually the admin's feet)
     * @param radius sphere radius in blocks
     */
    public static Piece capture(Location center, int radius, String biomeGroup, String name) {
        World w = center.getWorld();
        int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();
        int r2 = radius * radius;
        int shell = (radius - 2) * (radius - 2);

        Map<String, Integer> palette = new HashMap<>();
        Map<String, Integer> biomes = new HashMap<>();
        List<short[]> pos = new ArrayList<>();
        List<int[]> ids = new ArrayList<>();

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                int wy = cy + y;
                if (wy < w.getMinHeight() || wy >= w.getMaxHeight()) continue;
                for (int z = -radius; z <= radius; z++) {
                    int d2 = x * x + y * y + z * z;
                    if (d2 > r2) continue;
                    Block b = w.getBlockAt(cx + x, wy, cz + z);
                    Material m = b.getType();
                    if (m.isAir()) continue;
                    BlockData data = b.getBlockData();
                    // Fluids on the outer shell would pour off the island forever: skip them.
                    if (d2 > shell && isFluid(m, data)) continue;
                    String state = data.getAsString();
                    String biome = w.getBiome(cx + x, wy, cz + z).getKey().toString();
                    int bi = palette.computeIfAbsent(state, k -> palette.size());
                    int bio = biomes.computeIfAbsent(biome, k -> biomes.size());
                    pos.add(new short[]{(short) x, (short) y, (short) z});
                    ids.add(new int[]{bi, bio});
                }
            }
        }

        int n = pos.size();
        short[] dx = new short[n], dy = new short[n], dz = new short[n];
        int[] block = new int[n], biome = new int[n];
        for (int i = 0; i < n; i++) {
            dx[i] = pos.get(i)[0]; dy[i] = pos.get(i)[1]; dz[i] = pos.get(i)[2];
            block[i] = ids.get(i)[0]; biome[i] = ids.get(i)[1];
        }
        return new Piece(biomeGroup, name, radius, toArray(palette), toArray(biomes), dx, dy, dz, block, biome);
    }

    private static boolean isFluid(Material m, BlockData data) {
        return m == Material.WATER || m == Material.LAVA || m == Material.BUBBLE_COLUMN
                || (data instanceof Levelled && (m == Material.WATER || m == Material.LAVA));
    }

    private static String[] toArray(Map<String, Integer> map) {
        String[] out = new String[map.size()];
        map.forEach((k, v) -> out[v] = k);
        return out;
    }
}
