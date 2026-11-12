package dev.skyrealms.island;

import java.util.LinkedHashMap;
import java.util.Map;

/** One piece attached to an island, as an offset from the island centre, with its underside style. */
public record PiecePlacement(String biome, String variant, int dx, int dy, int dz, int rot, int radius, String style) {
    public PiecePlacement(String biome, String variant, int dx, int dy, int dz, int rot, int radius) {
        this(biome, variant, dx, dy, dz, rot, radius, "rock");
    }

    public PiecePlacement withStyle(String newStyle) {
        return new PiecePlacement(biome, variant, dx, dy, dz, rot, radius, newStyle == null ? "rock" : newStyle);
    }

    public Map<String, Object> serialize() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("biome", biome); m.put("variant", variant);
        m.put("dx", dx); m.put("dy", dy); m.put("dz", dz);
        m.put("rot", rot); m.put("radius", radius);
        if (!"rock".equals(style)) m.put("style", style);
        return m;
    }

    public static PiecePlacement deserialize(Map<?, ?> m) {
        Object st = m.get("style");
        return new PiecePlacement(String.valueOf(m.get("biome")), String.valueOf(m.get("variant")),
                num(m.get("dx")), num(m.get("dy")), num(m.get("dz")), num(m.get("rot")), num(m.get("radius")),
                st == null ? "rock" : String.valueOf(st));
    }

    private static int num(Object o) { return o instanceof Number n ? n.intValue() : 0; }
}
