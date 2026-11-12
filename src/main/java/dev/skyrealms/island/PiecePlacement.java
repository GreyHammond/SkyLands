package dev.skyrealms.island;

import java.util.LinkedHashMap;
import java.util.Map;

/** One piece attached to an island, as an offset from the island centre. */
public record PiecePlacement(String biome, String variant, int dx, int dy, int dz, int rot, int radius) {
    public Map<String, Object> serialize() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("biome", biome); m.put("variant", variant);
        m.put("dx", dx); m.put("dy", dy); m.put("dz", dz);
        m.put("rot", rot); m.put("radius", radius);
        return m;
    }

    public static PiecePlacement deserialize(Map<?, ?> m) {
        return new PiecePlacement(String.valueOf(m.get("biome")), String.valueOf(m.get("variant")),
                num(m.get("dx")), num(m.get("dy")), num(m.get("dz")), num(m.get("rot")), num(m.get("radius")));
    }

    private static int num(Object o) { return o instanceof Number n ? n.intValue() : 0; }
}
