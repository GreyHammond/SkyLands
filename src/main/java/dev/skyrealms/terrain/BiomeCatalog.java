package dev.skyrealms.terrain;

import dev.skyrealms.piece.PieceLibrary;
import org.bukkit.Material;

import java.util.*;

/** Every biome players can pick: the built-in generated ones, plus any captured custom biomes. */
public final class BiomeCatalog {
    private final PieceLibrary library;

    public BiomeCatalog(PieceLibrary library) { this.library = library; }

    public List<String> all() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (TerrainBiome b : TerrainBiome.values()) out.add(b.id);
        out.addAll(library.biomes());
        return new ArrayList<>(out);
    }

    public boolean exists(String id) {
        return TerrainBiome.byId(id) != null || !library.variants(id).isEmpty();
    }

    /** Oceans can only be added as expansions, never as a whole starting island. */
    public boolean startable(String id) {
        TerrainBiome b = TerrainBiome.byId(id);
        return b != null ? !b.isWater() : exists(id);
    }

    public boolean builtIn(String id) { return TerrainBiome.byId(id) != null; }

    public String pretty(String id) {
        TerrainBiome b = TerrainBiome.byId(id);
        if (b != null) return b.displayName;
        StringBuilder sb = new StringBuilder();
        for (String w : id.split("_")) {
            if (w.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    /** Built-in icon, or null to let the menu guess. */
    public Material icon(String id) {
        TerrainBiome b = TerrainBiome.byId(id);
        return b == null ? null : b.icon;
    }
}
