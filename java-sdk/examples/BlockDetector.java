import dev.minecontrol.sdk.MineControl;
import dev.minecontrol.sdk.CommandException;
import com.google.gson.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Persistent 70-block detector; no movement, clicks or execution slot. */
public class BlockDetector {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    public static boolean nearby(JsonObject a, JsonObject b) {
        if (!a.get("type").equals(b.get("type")) || !a.get("dimension").equals(b.get("dimension"))) return false;
        long x=(long)a.get("x").getAsInt()-b.get("x").getAsInt();
        long y=(long)a.get("y").getAsInt()-b.get("y").getAsInt();
        long z=(long)a.get("z").getAsInt()-b.get("z").getAsInt();
        return x*x+y*y+z*z <= 4900;
    }
    static void save(Path path, JsonObject data) throws Exception {
        Path temp = Files.createTempFile(path.getParent(), ".detections-", ".tmp");
        try {
            Files.writeString(temp, JSON.toJson(data));
            try { Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    public static void main(String[] args) throws Exception {
        Path directory=Path.of("block-detections"); Files.createDirectories(directory);
        Map<String, JsonObject> worlds=new HashMap<>();
        Map<String, Path> paths=new HashMap<>();
        Set<String> marked=new HashSet<>();
        long nextMark=0;
        try (var lockFile=FileChannel.open(directory.resolve(".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
             var lock=lockFile.tryLock();
             var mc=MineControl.connect(args.length==0 ? 25580 : Integer.parseInt(args[0]), MineControl.MacroRole.DETECTION,"目标方块检测")) {
            if (lock == null) throw new IllegalStateException("另一个方块检测程序正在使用同一记录目录");
            while (!mc.stopRequested()) {
                if (!mc.status().worldLoaded()) { Thread.sleep(250); continue; }
                JsonObject scan=mc.call("detection.scan",new JsonObject()).getAsJsonObject();
                String world=scan.get("world").getAsString(), dimension=scan.get("dimension").getAsString();
                if (!worlds.containsKey(world)) {
                    String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(world.getBytes(StandardCharsets.UTF_8)));
                    Path path=directory.resolve(hash+".json");
                    JsonObject data;
                    if (Files.exists(path)) {
                        data=JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                        if (!world.equals(data.get("world").getAsString()) || !data.get("records").isJsonArray()) throw new IllegalStateException("检测记录文件无效："+path);
                    } else { data=new JsonObject(); data.addProperty("world",world); data.addProperty("radius",70); data.add("records",new JsonArray()); }
                    worlds.put(world,data); paths.put(world,path);
                    System.out.println("检测记录："+path.toAbsolutePath());
                }
                JsonObject data=worlds.get(world); JsonArray records=data.getAsJsonArray("records");
                boolean dirty=false;
                for (JsonElement element:scan.getAsJsonArray("hits")) {
                    JsonObject hit=element.getAsJsonObject(); hit.addProperty("dimension",dimension);
                    boolean duplicate=false;
                    for (JsonElement saved:records) if (nearby(hit,saved.getAsJsonObject())) { duplicate=true; break; }
                    if (!duplicate) { hit.addProperty("found_at",java.time.Instant.now().toString()); records.add(hit); dirty=true; }
                }
                if (dirty) { save(paths.get(world),data); System.out.println("已保存，共 "+records.size()+" 个发现点"); }
                if (System.currentTimeMillis() >= nextMark) {
                    int count=0;
                    for (JsonElement element:records) {
                        JsonObject point=element.getAsJsonObject();
                        if (!dimension.equals(point.get("dimension").getAsString())) continue;
                        String key=world+point.toString(); if (marked.contains(key)) continue;
                        JsonObject request=point.deepCopy(); request.addProperty("world",world);
                        try { mc.call("detection.mark",request); marked.add(key); }
                        catch (Exception e) { System.out.println("路标待重试："+e.getMessage()); nextMark=System.currentTimeMillis()+10000; break; }
                        if (++count>=4) break;
                    }
                }
                Thread.sleep(100);
            }
            mc.exitReason("方块检测已停止，发现记录已保留");
        } catch (CommandException e) {
            if (!e.code().equals("STOPPING")) throw e;
            System.out.println("方块检测已停止，发现记录已保留");
        }
    }
}
