import dev.minecontrol.sdk.MineControl;
import dev.minecontrol.sdk.CommandException;
import com.google.gson.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Single-pass detector; spawners retain every distinct position, other types use 70-block dedup. */
public class BlockDetector {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    public static boolean nearby(JsonObject a, JsonObject b) {
        if (!a.get("type").equals(b.get("type")) || !a.get("dimension").equals(b.get("dimension"))) return false;
        long x=(long)a.get("x").getAsInt()-b.get("x").getAsInt();
        long y=(long)a.get("y").getAsInt()-b.get("y").getAsInt();
        long z=(long)a.get("z").getAsInt()-b.get("z").getAsInt();
        if ("spawner".equals(a.get("type").getAsString())) return x == 0 && y == 0 && z == 0;
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
        try (var lockFile=FileChannel.open(directory.resolve(".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
             var lock=lockFile.tryLock();
             var mc=MineControl.connect(args.length==0 ? 25580 : Integer.parseInt(args[0]), MineControl.MacroRole.DETECTION,"目标方块检测")) {
            if (lock == null) throw new IllegalStateException("另一个方块检测程序正在使用同一记录目录");
            run(mc,directory);
        } catch (CommandException e) {
            if (!e.code().equals("STOPPING")) throw e;
            System.out.println("方块检测已停止，发现记录已保留");
        }
    }
    static void run(MineControl mc, Path directory) throws Exception {
        mc.exitReason("检查未完成");
        if (!mc.status().worldLoaded()) { mc.exitReason("未进入世界"); return; }
        JsonObject request=new JsonObject(); request.addProperty("begin",true);
        JsonObject data=null;
        Path path=null;
        String world=null, dimension=null;
        while (!mc.stopRequested()) {
            JsonObject scan=mc.call("detection.scan",request).getAsJsonObject();
            if (data==null) {
                world=scan.get("world").getAsString(); dimension=scan.get("dimension").getAsString();
                String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(world.getBytes(StandardCharsets.UTF_8)));
                path=directory.resolve(hash+".json");
                if (Files.exists(path)) {
                    data=JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                    if (!world.equals(data.get("world").getAsString()) || !data.get("records").isJsonArray()) throw new IllegalStateException("检测记录文件无效："+path);
                } else { data=new JsonObject(); data.addProperty("world",world); data.addProperty("radius",70); data.add("records",new JsonArray()); }
                System.out.println("检测记录："+path.toAbsolutePath());
            }
            JsonArray records=data.getAsJsonArray("records");
            boolean dirty=false;
            for (JsonElement element:scan.getAsJsonArray("hits")) {
                JsonObject hit=element.getAsJsonObject(); hit.addProperty("dimension",dimension);
                boolean duplicate=false;
                for (JsonElement saved:records) if (nearby(hit,saved.getAsJsonObject())) { duplicate=true; break; }
                if (!duplicate) { hit.addProperty("found_at",java.time.Instant.now().toString()); records.add(hit); dirty=true;
                    System.out.println("[地图记录] 新发现，坐标与类型="+hit); }
                else System.out.println("[地图记录] "+("spawner".equals(hit.get("type").getAsString()) ? "刷怪笼坐标已记录" : "同类70格三维范围去重")+"，跳过="+hit);
            }
            if (dirty) { save(path,data); System.out.println("[地图记录] 文件保存成功，文件="+path.toAbsolutePath()+"，发现点="+records.size()); }
            if (scan.get("complete").getAsBoolean()) {
                for (JsonElement element:records) {
                    if (mc.stopRequested()) { mc.exitReason("检查已停止"); return; }
                    JsonObject point=element.getAsJsonObject();
                    if (!dimension.equals(point.get("dimension").getAsString())) continue;
                    JsonObject mark=point.deepCopy(); mark.addProperty("world",world);
                    try {
                        System.out.println("[地图记录] 请求Xaero标注="+mark);
                        var result=mc.call("detection.mark",mark);
                        System.out.println("[地图记录] Xaero操作完成，坐标="+point+"，结果="+result);
                    }
                    catch (CommandException e) {
                        if (e.code().equals("STOPPING") || e.code().equals("WORLD_LOADING") || e.code().equals("INVALID_ARGUMENT")) throw e;
                        System.out.println("路标未能添加，坐标已保留："+e.getMessage());
                        continue;
                    }
                }
                mc.exitReason("检查完成");
                System.out.println("检查完成");
                return;
            }
            request=new JsonObject(); request.add("scan_id",scan.get("scan_id"));
            Thread.sleep(100);
        }
        mc.exitReason("检查已停止");
    }

}
