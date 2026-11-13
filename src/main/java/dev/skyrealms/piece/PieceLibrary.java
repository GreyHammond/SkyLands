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

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;

/**
 * Loads pieces from plugins/SkyRealms/pieces/&lt;biome&gt;/&lt;variant&gt;.skyp.
 * Every folder that contains at least one piece is a biome players can pick.
 */
public final class PieceLibrary {
    private final File root;
    private final Logger log;
    private final Map<String, List<Piece>> byBiome = new TreeMap<>();

    public PieceLibrary(File root, Logger log) {
        this.root = root;
        this.log = log;
    }

    public void reload() {
        byBiome.clear();
        root.mkdirs();
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null) return;
        for (File dir : dirs) {
            File[] files = dir.listFiles((d, n) -> n.endsWith(".skyp"));
            if (files == null) continue;
            for (File f : files) {
                try {
                    byBiome.computeIfAbsent(dir.getName().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                            .add(Piece.read(f, dir.getName().toLowerCase(Locale.ROOT)));
                } catch (IOException e) {
                    log.warning("Could not load piece " + f + ": " + e.getMessage());
                }
            }
        }
        byBiome.values().removeIf(List::isEmpty);
        log.info("Loaded " + byBiome.values().stream().mapToInt(List::size).sum()
                + " pieces across " + byBiome.size() + " biomes.");
    }

    public Set<String> biomes() { return byBiome.keySet(); }

    public List<Piece> variants(String biome) { return byBiome.getOrDefault(biome, List.of()); }

    public Piece random(String biome, Random rng) {
        List<Piece> list = variants(biome);
        return list.isEmpty() ? null : list.get(rng.nextInt(list.size()));
    }

    public Piece find(String biome, String name) {
        for (Piece p : variants(biome)) if (p.name.equals(name)) return p;
        return null;
    }

    public File fileFor(String biome, String name) {
        return new File(new File(root, biome), name + ".skyp");
    }

    public void add(Piece piece) {
        List<Piece> list = byBiome.computeIfAbsent(piece.biomeGroup, k -> new ArrayList<>());
        list.removeIf(p -> p.name.equals(piece.name));
        list.add(piece);
    }

    public String nextName(String biome) {
        int i = 1;
        while (fileFor(biome, biome + "_" + i).exists()) i++;
        return biome + "_" + i;
    }
}
