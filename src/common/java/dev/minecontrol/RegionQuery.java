package dev.minecontrol;

import com.google.gson.*;
import java.util.function.BiPredicate;

/** Bounded client-only region query with AI Helper MCBLUEPRINT v3 block syntax. */
public final class RegionQuery {
    public static final int MAX_BLOCKS = 32768;
    public static final class Error extends IllegalArgumentException {
        public final String code;
        public Error(String code, String message) { super(message); this.code = code; }
    }
    public record Range(int x1, int y1, int z1, int x2, int y2, int z2) {
        public long volume() { return (long)(x2-x1+1)*(y2-y1+1)*(z2-z1+1); }
    }
    @FunctionalInterface public interface Reader { String block(int x, int y, int z); }
    private RegionQuery() {}
    public static Range parse(JsonObject request) {
        int[] a = corner(request.get("from")), b = corner(request.get("to"));
        var r = new Range(Math.min(a[0],b[0]), Math.min(a[1],b[1]), Math.min(a[2],b[2]),
                Math.max(a[0],b[0]), Math.max(a[1],b[1]), Math.max(a[2],b[2]));
        // Check each axis before multiplication, so even extreme inputs cannot overflow volume.
        if ((long)r.x2-r.x1+1 > MAX_BLOCKS || (long)r.y2-r.y1+1 > MAX_BLOCKS || (long)r.z2-r.z1+1 > MAX_BLOCKS || r.volume() > MAX_BLOCKS)
            throw new Error("REGION_TOO_LARGE", "Region exceeds " + MAX_BLOCKS + " blocks; split the query");
        return r;
    }
    private static int[] corner(JsonElement element) {
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() != 3)
            throw new Error("INVALID_ARGUMENT", "from/to must be [x,y,z] integer arrays");
        int[] result = new int[3];
        for (int i=0;i<3;i++) {
            var v = element.getAsJsonArray().get(i);
            if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) throw new Error("INVALID_ARGUMENT", "Coordinates must be integers");
            try { result[i] = new java.math.BigDecimal(v.getAsString()).intValueExact(); }
            catch (ArithmeticException | NumberFormatException e) { throw new Error("INVALID_ARGUMENT", "Coordinates must be 32-bit integers"); }
            if (result[i] < -30000000 || result[i] > 30000000) throw new Error("INVALID_ARGUMENT", "Coordinates exceed supported world bounds");
        }
        return result;
    }
    public static JsonObject scan(Range r, int minY, int maxYExclusive, BiPredicate<Integer,Integer> loaded, Reader reader, String dimension, long tick) {
        if (r.y1 < minY || r.y2 >= maxYExclusive) throw new Error("OUT_OF_HEIGHT", "Region must be within world build height");
        for (int cz = Math.floorDiv(r.z1,16); cz <= Math.floorDiv(r.z2,16); cz++)
            for (int cx = Math.floorDiv(r.x1,16); cx <= Math.floorDiv(r.x2,16); cx++)
                if (!loaded.test(cx,cz)) throw new Error("CHUNK_NOT_LOADED", "Client chunk not loaded: " + cx + "," + cz);
        var text = new StringBuilder("# MCBLUEPRINT v3\n# name: region_query\n# size: ")
                .append(r.x2-r.x1+1).append('x').append(r.y2-r.y1+1).append('x').append(r.z2-r.z1+1)
                .append("\n# source: client_snapshot; air omitted; unloaded chunks rejected\n\n## BLOCKS\n\n");
        int count = 0;
        for (int y=r.y1;y<=r.y2;y++) {
            boolean header = false;
            for (int z=r.z1;z<=r.z2;z++) for (int x=r.x1;x<=r.x2;x++) {
                String block = reader.block(x,y,z);
                if (block == null) continue;
                if (!header) { text.append("# --- y=").append(y).append(" ---\n"); header = true; }
                text.append(x).append(',').append(y).append(',').append(z).append("   ").append(block).append('\n'); count++;
                if (text.length() > 700000) throw new Error("RESPONSE_TOO_LARGE", "Region text too large; split the query");
            }
        }
        var result = new JsonObject(); result.addProperty("format", "MCBLUEPRINT v3");
        result.addProperty("source", "client_snapshot"); result.addProperty("dimension", dimension); result.addProperty("tick", tick);
        result.add("from", new Gson().toJsonTree(new int[]{r.x1,r.y1,r.z1})); result.add("to", new Gson().toJsonTree(new int[]{r.x2,r.y2,r.z2}));
        result.addProperty("volume", r.volume()); result.addProperty("block_count", count); result.addProperty("air_omitted", true);
        result.addProperty("block_entity_data_included", false); result.addProperty("blueprint", text.toString());
        try { return ObservationData.bounded(result); }
        catch (IllegalStateException e) { throw new Error("RESPONSE_TOO_LARGE", "Region response too large; split the query"); }
    }
}
