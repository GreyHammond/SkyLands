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
package dev.skyrealms.piece;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.StructureRotation;

import java.io.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * A captured blob of terrain (normally a sphere taken from a real biome).
 * Only non-air blocks are stored, so pasting never carves holes into neighbours.
 *
 * File format (.skyp, gzip): magic, version, radius, block palette,
 * biome palette, then one record per block: dx, dy, dz, block idx, biome idx.
 */
public final class Piece {
    private static final int MAGIC = 0x534B5950; // "SKYP"
    private static final int VERSION = 1;

    public final String biomeGroup;   // folder name, e.g. "forest"
    public final String name;         // variant name, e.g. "forest_2"
    public final int radius;
    final String[] palette;
    final String[] biomePalette;
    final short[] dx, dy, dz;
    final int[] block, biome;

    private final BlockData[][] rotatedPalette = new BlockData[4][];
    private Biome[] resolvedBiomes;

    Piece(String biomeGroup, String name, int radius, String[] palette, String[] biomePalette,
          short[] dx, short[] dy, short[] dz, int[] block, int[] biome) {
        this.biomeGroup = biomeGroup;
        this.name = name;
        this.radius = radius;
        this.palette = palette;
        this.biomePalette = biomePalette;
        this.dx = dx; this.dy = dy; this.dz = dz;
        this.block = block; this.biome = biome;
    }

    public int size() { return dx.length; }

    /** Block palette rotated clockwise {@code rot} * 90 degrees (parsed lazily, cached). */
    public synchronized BlockData[] palette(int rot) {
        BlockData[] p = rotatedPalette[rot];
        if (p == null) {
            p = new BlockData[palette.length];
            for (int i = 0; i < palette.length; i++) {
                BlockData d = Bukkit.createBlockData(palette[i]);
                if (rot != 0) d.rotate(ROTATIONS[rot]);
                p[i] = d;
            }
            rotatedPalette[rot] = p;
        }
        return p;
    }

    public synchronized Biome[] biomes() {
        if (resolvedBiomes == null) {
            var reg = RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME);
            resolvedBiomes = new Biome[biomePalette.length];
            for (int i = 0; i < biomePalette.length; i++) {
                NamespacedKey key = NamespacedKey.fromString(biomePalette[i]);
                resolvedBiomes[i] = key == null ? null : reg.get(key);
            }
        }
        return resolvedBiomes;
    }

    private static final StructureRotation[] ROTATIONS = {
            StructureRotation.NONE, StructureRotation.CLOCKWISE_90,
            StructureRotation.CLOCKWISE_180, StructureRotation.COUNTERCLOCKWISE_90};

    /** Rotate an offset clockwise rot*90 degrees (matches Minecraft's StructureRotation). */
    public static int rotX(int x, int z, int rot) {
        return switch (rot) { case 1 -> -z; case 2 -> -x; case 3 -> z; default -> x; };
    }

    public static int rotZ(int x, int z, int rot) {
        return switch (rot) { case 1 -> x; case 2 -> -z; case 3 -> -x; default -> z; };
    }

    // ---- IO ----------------------------------------------------------------

    public void write(File file) throws IOException {
        file.getParentFile().mkdirs();
        try (var out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(file))))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(radius);
            out.writeInt(palette.length);
            for (String s : palette) out.writeUTF(s);
            out.writeInt(biomePalette.length);
            for (String s : biomePalette) out.writeUTF(s);
            out.writeInt(dx.length);
            for (int i = 0; i < dx.length; i++) {
                out.writeShort(dx[i]); out.writeShort(dy[i]); out.writeShort(dz[i]);
                out.writeInt(block[i]); out.writeShort(biome[i]);
            }
        }
    }

    public static Piece read(File file, String biomeGroup) throws IOException {
        String name = file.getName().replaceFirst("\\.skyp$", "");
        try (var in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(file))))) {
            if (in.readInt() != MAGIC) throw new IOException("Not a SkyRealms piece: " + file);
            int version = in.readInt();
            if (version != VERSION) throw new IOException("Unsupported piece version " + version + ": " + file);
            int radius = in.readInt();
            String[] palette = new String[in.readInt()];
            for (int i = 0; i < palette.length; i++) palette[i] = in.readUTF();
            String[] biomes = new String[in.readInt()];
            for (int i = 0; i < biomes.length; i++) biomes[i] = in.readUTF();
            int n = in.readInt();
            short[] dx = new short[n], dy = new short[n], dz = new short[n];
            int[] block = new int[n], biome = new int[n];
            for (int i = 0; i < n; i++) {
                dx[i] = in.readShort(); dy[i] = in.readShort(); dz[i] = in.readShort();
                block[i] = in.readInt(); biome[i] = in.readShort();
            }
            return new Piece(biomeGroup, name, radius, palette, biomes, dx, dy, dz, block, biome);
        }
    }
}
