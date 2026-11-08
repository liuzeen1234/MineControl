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
        var spawner=point("spawner","overworld",-100,0,-100);
        require(BlockDetector.nearby(spawner,point("spawner","overworld",-100,0,-100)));
        require(!BlockDetector.nearby(spawner,point("spawner","overworld",-99,0,-100)));
        require(!BlockDetector.nearby(spawner,point("spawner","overworld",-100,1,-100)));
        require(!BlockDetector.nearby(spawner,point("spawner","overworld",-100,0,-99)));
        require(!BlockDetector.nearby(spawner,point("spawner","nether",-100,0,-100)));
        require(BlockDetector.nearby(origin,point("rail","overworld",-30,0,-100)));
        require(!BlockDetector.nearby(origin,point("rail","overworld",-29,0,-100)));
        require(BlockDetector.nearby(origin,point("rail","overworld",-100,70,-100)));
        require(!BlockDetector.nearby(origin,point("rail","overworld",-100,71,-100)));
        require(BlockDetector.nearby(origin,point("rail","overworld",-58,56,-100)));
        require(!BlockDetector.nearby(origin,point("rail","overworld",-58,57,-100)));
        require(!BlockDetector.nearby(origin,point("iron_bars","overworld",-100,0,-100)));
        require(!BlockDetector.nearby(origin,point("rail","nether",-100,0,-100)));
        require(!BlockDetector.nearby(point("rail","overworld",-30000000,0,0),point("rail","overworld",30000000,0,0)));
        var wetSponge=point("wet_sponge","overworld",0,0,0);
        require(BlockDetector.nearby(wetSponge,point("wet_sponge","overworld",42,56,0)));
        require(!BlockDetector.nearby(wetSponge,point("wet_sponge","overworld",42,57,0)));
        require(!BlockDetector.nearby(wetSponge,point("wet_sponge","nether",0,0,0)));
        var cryingObsidian=point("crying_obsidian","overworld",0,0,0);
        require(BlockDetector.nearby(cryingObsidian,point("crying_obsidian","overworld",0,70,0)));
        require(!BlockDetector.nearby(cryingObsidian,point("crying_obsidian","overworld",0,71,0)));
        Path dir=Files.createTempDirectory("detector-test"); Path path=dir.resolve("world.json");
        try { BlockDetector.save(path,origin); require(JsonParser.parseString(Files.readString(path)).equals(origin));
            BlockDetector.save(path,point("spawner","end",0,90,0)); require(!JsonParser.parseString(Files.readString(path)).equals(origin));
        } finally { Files.deleteIfExists(path); Files.delete(dir); }
        BlockDetectorOnceTest.run();
        System.out.println("Block detector: 70-block 3D boundary, negative coordinates, type/dimension isolation, atomic persistence passed");
    }
}
