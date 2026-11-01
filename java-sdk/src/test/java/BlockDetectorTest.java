import com.google.gson.*;
import java.nio.file.*;
public class BlockDetectorTest {
    static JsonObject point(String type, String dimension, int x,int y,int z) {
        var p=new JsonObject(); p.addProperty("type",type); p.addProperty("dimension",dimension);
        p.addProperty("x",x); p.addProperty("y",y); p.addProperty("z",z); return p;
    }
    static void require(boolean value) { if(!value) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        var origin=point("rail","overworld",-100,0,-100);
        require(BlockDetector.nearby(origin,point("rail","overworld",-30,0,-100)));
        require(!BlockDetector.nearby(origin,point("rail","overworld",-29,0,-100)));
        require(BlockDetector.nearby(origin,point("rail","overworld",-100,70,-100)));
        require(!BlockDetector.nearby(origin,point("rail","overworld",-100,71,-100)));
        require(BlockDetector.nearby(origin,point("rail","overworld",-58,56,-100)));
        require(!BlockDetector.nearby(origin,point("rail","overworld",-58,57,-100)));
        require(!BlockDetector.nearby(origin,point("iron_bars","overworld",-100,0,-100)));
        require(!BlockDetector.nearby(origin,point("rail","nether",-100,0,-100)));
        require(!BlockDetector.nearby(point("rail","overworld",-30000000,0,0),point("rail","overworld",30000000,0,0)));
        Path dir=Files.createTempDirectory("detector-test"); Path path=dir.resolve("world.json");
        try { BlockDetector.save(path,origin); require(JsonParser.parseString(Files.readString(path)).equals(origin));
            BlockDetector.save(path,point("spawner","end",0,90,0)); require(!JsonParser.parseString(Files.readString(path)).equals(origin));
        } finally { Files.deleteIfExists(path); Files.delete(dir); }
        System.out.println("Block detector: 70-block 3D boundary, negative coordinates, type/dimension isolation, atomic persistence passed");
    }
}
