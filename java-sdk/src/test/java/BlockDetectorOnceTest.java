import dev.minecontrol.sdk.MineControl;
import com.google.gson.*;
import java.net.*;
import java.nio.file.*;
import java.io.*;
import java.util.concurrent.atomic.*;

/** Actual SDK transport: batching finishes exactly once, including an empty scan. */
public final class BlockDetectorOnceTest {
    public static void run() throws Exception {
        for (boolean empty : new boolean[]{false,true}) {
            Path dir=Files.createTempDirectory("block-once-");
            var scans=new AtomicInteger(); var marks=new AtomicInteger();
            var exit=new AtomicReference<String>(); var failure=new AtomicReference<Throwable>();
            try (var listener=new ServerSocket(0,16,InetAddress.getLoopbackAddress())) {
                var server=new Thread(() -> {
                    while (!listener.isClosed()) {
                        try (var socket=listener.accept()) {
                            var request=JsonParser.parseString(new BufferedReader(new InputStreamReader(socket.getInputStream())).readLine()).getAsJsonObject();
                            String command=request.get("command").getAsString(); var result=new JsonObject();
                            switch(command) {
                                case "ping" -> result.addProperty("protocol",1);
                                case "macro.register" -> result.addProperty("macro_token","test");
                                case "status" -> result.addProperty("world_loaded",true);
                                case "macro.heartbeat" -> result.addProperty("stopping",false);
                                case "detection.scan" -> {
                                    int batch=scans.incrementAndGet();
                                    if (batch==1 && !request.get("begin").getAsBoolean()) throw new AssertionError("missing begin");
                                    if (batch>1 && (!request.get("scan_id").getAsString().equals("one") || batch>2)) throw new AssertionError("unexpected rescan");
                                    result.addProperty("world","test-world"); result.addProperty("dimension","overworld");
                                    result.addProperty("scan_id","one"); result.addProperty("complete",empty || batch==2);
                                    var hits=new JsonArray();
                                    if (!empty) hits.add(BlockDetectorTest.point("vault","overworld",batch==1?0:70,100,0));
                                    result.add("hits",hits);
                                }
                                case "detection.mark" -> marks.incrementAndGet();
                                case "macro.exit" -> exit.set(request.get("reason").getAsString());
                                default -> throw new AssertionError("Unexpected command: "+command);
                            }
                            var response=new JsonObject(); response.add("id",request.get("id"));response.addProperty("ok",true);response.add("result",result);
                            socket.getOutputStream().write((response+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        } catch(Exception e) { if (!listener.isClosed()) failure.set(e); }
                        catch(Throwable e) { failure.set(e); }
                    }
                });
                server.setDaemon(true);server.start();
                try (var mc=MineControl.connect(listener.getLocalPort(),MineControl.MacroRole.DETECTION,"test")) { BlockDetector.run(mc,dir); }
                if (failure.get()!=null) throw new AssertionError(failure.get());
                if (!"检查完成".equals(exit.get()) || scans.get()!=(empty?1:2) || marks.get()!=(empty?0:1)) throw new AssertionError("single-pass completion");
                if (!empty) {
                    try(var paths=Files.list(dir)) {
                        var data=JsonParser.parseString(Files.readString(paths.findFirst().orElseThrow())).getAsJsonObject();
                        if(data.getAsJsonArray("records").size()!=1) throw new AssertionError("70-block dedup");
                    }
                }
                listener.close();server.join(2000);
            } finally {
                try(var paths=Files.walk(dir)) { for(var path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(path); }
            }
        }
        System.out.println("Single-pass/empty scan completion, saved results, dedup and exit reason passed");
    }
}
