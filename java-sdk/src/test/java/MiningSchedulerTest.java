import com.google.gson.*;
import dev.minecontrol.sdk.MineControl;
import dev.minecontrol.sdk.Snapshots.Position;
import java.net.*;
import java.io.*;
import java.util.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;

/** Drives the actual scheduler over SDK TCP through threshold, busy, ownership and dimension cases. */
public final class MiningSchedulerTest {
    static Position pos(double x,double y,double z,String dimension) { return new Position(x,y,z,null,0,0,dimension,0); }
    static JsonObject macro(String id,String file,String role) {
        var m=new JsonObject();m.addProperty("instance_id",id);m.addProperty("file",file);m.addProperty("role",role);m.addProperty("stopping",false);return m;
    }
    public static void main(String[] args) throws Exception {
        if (MiningScheduler.moved100(pos(0,0,0,"o"),pos(99.99,0,0,"o"))) throw new AssertionError("below 100");
        if (!MiningScheduler.moved100(pos(-100,0,0,"o"),pos(-40,80,0,"o"))) throw new AssertionError("3D boundary");
        if (!MiningScheduler.moved100(pos(0,0,0,"o"),pos(0,100,0,"o"))) throw new AssertionError("vertical");
        if (MiningScheduler.moved100(pos(0,0,0,"o"),pos(1000,0,0,"n"))) throw new AssertionError("dimension");
        var gson=new Gson();
        for(String mode:List.of("adventure","spectator","survival")) {
            var observation=gson.fromJson("{\"world_loaded\":true,\"active\":true,\"player\":{\"alive\":true,\"game_mode\":\""+mode+"\"}}",dev.minecontrol.sdk.Snapshots.Observation.class);
            if(MiningScheduler.readyForMining(observation)!=mode.equals("survival"))throw new AssertionError("Login readiness: "+mode);
        }
        if(MiningScheduler.readyForMining(null))throw new AssertionError("Missing world accepted");
        scenario(false);
        scenario(true);
        scenario(false,true);
        var recovery=new MiningScheduler.RecoveryProgress();
        var start=pos(0,-57,0,"overworld");
        if(recovery.healthy(start,false,0) || recovery.healthy(start,false,60_000_000_000L))
            throw new AssertionError("A slow stationary failure was declared healthy");
        if(recovery.healthy(pos(1746,59,-96386,"overworld"),true,61_000_000_000L))
            throw new AssertionError("Warehouse teleport was declared healthy");
        if(recovery.healthy(start,false,62_000_000_000L) ||
                !recovery.healthy(pos(2,-57,0,"overworld"),false,73_000_000_000L))
            throw new AssertionError("Healthy mining movement did not recover");
        var limiter=new MiningScheduler.Restarts();
        for(int n=1;n<=6;n++) if(limiter.attempt("miner",n)!=n)throw new AssertionError("restart limit");
        if(limiter.attempt("detector",7)!=1)throw new AssertionError("independent limit");
        if(limiter.attempt("miner",10_000_000_006L)!=1)throw new AssertionError("rolling window");
        var directory=Files.createTempDirectory("scheduler-route-reset-");
        try {
            var route=directory.resolve("fishbone-route-state.json");
            var unload=directory.resolve("fishbone-unload-state.json");
            Files.writeString(route,"original route");Files.writeString(unload,"return position");
            if(MiningScheduler.resetRoute(directory)!=null || !Files.exists(route))throw new AssertionError("Reset during unloading");
            Files.delete(unload);
            var backup=MiningScheduler.resetRoute(directory);
            if(Files.exists(route) || !Files.readString(backup).equals("original route"))throw new AssertionError("Backup/reset failed");
            Files.delete(backup);
            limiter.minerRetries=5;limiter.routeReset=true;limiter.minerHealthy();
            if(limiter.minerRetries!=0 || limiter.routeReset)throw new AssertionError("Healthy run did not clear failure streak");
        } finally { try(var paths=Files.list(directory)){for(var path:paths.toList())Files.deleteIfExists(path);}Files.delete(directory); }
        System.out.println("Scheduler passed including warehouse teleport exclusion");
    }
    static void scenario(boolean unloading) throws Exception {
        scenario(unloading,false);
    }
    static void scenario(boolean unloading,boolean startMiner) throws Exception {
        var marker=Path.of("fishbone-unload-state.json");
        if(Files.exists(marker)) throw new AssertionError("Existing unload state; refusing to overwrite");
        try {
        var starts=new ArrayList<Integer>();var stops=new ArrayList<String>();var failure=new AtomicReference<Throwable>();
        var minerStarts=new java.util.concurrent.atomic.AtomicInteger();
        try(var listener=new ServerSocket(0,16,InetAddress.getLoopbackAddress())) {
            var server=new Thread(() -> {
                int frame=0;boolean followup=false;String owned=null;
                while(!listener.isClosed()) {
                    try(var socket=listener.accept()) {
                        var request=JsonParser.parseString(new BufferedReader(new InputStreamReader(socket.getInputStream())).readLine()).getAsJsonObject();
                        var object=new JsonObject();JsonElement result=object;
                        String command=request.get("command").getAsString();
                        switch(command) {
                            case "ping" -> object.addProperty("protocol",1);
                            case "macro.register" -> object.addProperty("macro_token","scheduler-test");
                            case "macro.heartbeat" -> object.addProperty("stopping",frame>=14);
                            case "bus.poll" -> {object.add("messages",new JsonArray());object.addProperty("dropped",0);}
                            case "program.list" -> {
                                if(startMiner && minerStarts.get()==0) { result=new JsonArray();break; }
                                if(!followup) { frame++; if(frame==5 || frame==6 || frame==8 || frame==9)owned=null; } else followup=false;
                                if(unloading && frame==3) Files.writeString(marker,"test warehouse trip");
                                if(unloading && frame==5) Files.deleteIfExists(marker);
                                var list=new JsonArray();
                                if(frame!=11 && frame!=14)list.add(macro(frame>=12?"miner-new":"miner","fishbone-mining.jar","execution"));
                                if(owned!=null && (frame==3 || frame==4 || frame==5 || frame==8 || frame==10 || frame==11))list.add(macro(owned,"block-detector.jar","detection"));
                                if(frame==7 || frame==14)list.add(macro("manual","block-detector.jar","detection"));
                                result=list;
                            }
                            case "status" -> object.addProperty("world_loaded",true);
                            case "observe" -> {
                                object.addProperty("world_loaded",true);object.addProperty("active",true);
                                var player=new JsonObject();player.addProperty("alive",true);player.addProperty("game_mode","survival");object.add("player",player);
                            }
                            case "position" -> {
                                int[] x={0,0,99,100,200,200,299,300,300,10000,10100,0,10000,10000};
                                object.addProperty("x",x[frame]);object.addProperty("y",0);object.addProperty("z",0);
                                object.addProperty("dimension",frame>=9?"nether":"overworld");
                            }
                            case "program.start" -> {
                                if(request.get("file").getAsString().equals("fishbone-mining.jar")) {
                                    if(frame!=0 && frame!=11)throw new AssertionError("unexpected miner start");
                                    minerStarts.incrementAndGet();
                                    break;
                                }
                                if(!request.get("file").getAsString().equals("block-detector.jar"))throw new AssertionError("wrong program");
                                starts.add(frame);owned="owned-"+frame;followup=true;
                            }
                            case "program.stop" -> { stops.add(request.get("instance_id").getAsString());owned=null; }
                            case "macro.exit" -> {}
                            default -> throw new AssertionError("Unexpected command "+command);
                        }
                        var reply=new JsonObject();reply.add("id",request.get("id"));reply.addProperty("ok",true);reply.add("result",result);
                        socket.getOutputStream().write((reply+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    } catch(Throwable e) { if(!listener.isClosed())failure.set(e); }
                }
            });server.setDaemon(true);server.start();
            try(var mc=MineControl.connect(listener.getLocalPort(),MineControl.MacroRole.SCHEDULER,"scheduler test")) { MiningScheduler.run(mc); }
            if(failure.get()!=null)throw new AssertionError(failure.get());
            if(minerStarts.get()!=(startMiner?2:1))throw new AssertionError("Wrong miner startup count: "+minerStarts);
            if(!starts.equals(unloading?List.of(5,8,10,12,13):List.of(3,5,8,10,12,13)))throw new AssertionError("Restart lost displacement baseline: "+starts);
            if(!stops.equals(List.of("owned-10")))throw new AssertionError("Wrong stopped owners: "+stops);
            listener.close();server.join(2000);
        }
        } finally { if(unloading) Files.deleteIfExists(marker); }
        System.out.println("100-block scheduler: threshold, 3D/negative positions, busy/manual detector, dimension reset, mining restart and ownership passed");
    }
}
