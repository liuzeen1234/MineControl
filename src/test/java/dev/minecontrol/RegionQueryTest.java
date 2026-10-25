package dev.minecontrol;

import com.google.gson.*;
import java.util.*;

public final class RegionQueryTest {
    private static RegionQuery.Range range(String text) { return RegionQuery.parse(JsonParser.parseString(text).getAsJsonObject()); }
    private static void require(boolean value) { if (!value) throw new AssertionError(); }
    private static void error(String code, Runnable action) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (RegionQuery.Error e) { require(e.code.equals(code)); }
    }
    public static void run() {
        var range = range("{\"from\":[0,65,0],\"to\":[-1,64,-1]}");
        require(range.volume() == 8 && range.x1() == -1 && range.y2() == 65);
        var chunks = new ArrayList<String>();
        var result = RegionQuery.scan(range, -64, 320, (x,z) -> { chunks.add(x+","+z); return true; },
            (x,y,z) -> x == -1 && y == 64 && z == -1 ? "example:custom_block   facing=north" : null, "minecraft:overworld", 3);
        require(result.get("block_count").getAsInt() == 1);
        require(chunks.equals(List.of("-1,-1","0,-1","-1,0","0,0")));
        require(result.get("blueprint").getAsString().contains("-1,64,-1   example:custom_block   facing=north\n"));
        var top = range("{\"from\":[0,319,0],\"to\":[0,319,0]}");
        require(RegionQuery.scan(top,-64,320,(x,z)->true,(x,y,z)->null,"test",0).get("block_count").getAsInt() == 0);
        error("OUT_OF_HEIGHT", () -> RegionQuery.scan(top,-64,319,(x,z)->true,(x,y,z)->null,"test",0));
        error("CHUNK_NOT_LOADED", () -> RegionQuery.scan(range,-64,320,(x,z)->false,(x,y,z)->{throw new AssertionError("read unloaded chunk");},"test",0));
        for (String text : List.of("{}", "{\"from\":[0.1,0,0],\"to\":[0,0,0]}", "{\"from\":[0,0],\"to\":[0,0,0]}", "{\"from\":[2147483648,0,0],\"to\":[0,0,0]}"))
            error("INVALID_ARGUMENT", () -> range(text));
        error("REGION_TOO_LARGE", () -> range("{\"from\":[-30000000,-30000000,-30000000],\"to\":[30000000,30000000,30000000]}"));
        error("RESPONSE_TOO_LARGE", () -> RegionQuery.scan(top,-64,320,(x,z)->true,(x,y,z)->"x".repeat(700001),"test",0));
        System.out.println("Region bounds/chunks/v3/limits checks passed");
    }
}
