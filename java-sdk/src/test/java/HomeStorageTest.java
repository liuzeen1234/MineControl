import com.google.gson.*;
import dev.minecontrol.sdk.MineControl;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the real unload routine via the SDK, including delayed home commands and full-box waiting and asynchronous transport. */
public class HomeStorageTest {
    static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
    static final Gson JSON=new Gson();
    static JsonObject object(Object value) { return JSON.toJsonTree(value).getAsJsonObject(); }
    static class World implements AutoCloseable {
        final ServerSocket server=new ServerSocket(0,16,InetAddress.getLoopbackAddress());
        final AtomicReference<Throwable> failure=new AtomicReference<>();
        final List<String> commands=new ArrayList<>();
        final int[] chest=new int[54],cargo=new int[27];
        final Thread thread;
        long tick;int state,opens,pending,staleFailures,noProgress,recoveryPolls;
        boolean wounded;
        int transportChecks,fullSnapshots;boolean transport;
        double x=.5,z=.5,yaw,pitch;boolean menu,warehousePending;final boolean west;
        World(int free,boolean west) throws Exception {
            this.west=west;
            Arrays.fill(cargo,0,25,1);int occupied=3456-free;
            for(int i=0;i<54;i++) { chest[i]=Math.min(64,occupied);occupied-=chest[i]; }
            thread=new Thread(()-> {
                while(!server.isClosed()) try(var socket=server.accept()) {
                    var request=JsonParser.parseString(new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8)).readLine()).getAsJsonObject();
                    var reply=new JsonObject();reply.add("id",request.get("id"));
                    try { reply.add("result",handle(request));reply.addProperty("ok",true); }
                    catch(dev.minecontrol.sdk.CommandException error) {reply.addProperty("ok",false);reply.add("error",object(Map.of("code",error.code(),"message","simulated stale snapshot")));}
                    catch(Throwable error) { failure.set(error);reply.addProperty("ok",false);reply.add("error",object(Map.of("code","TEST_ERROR","message",error.toString()))); }
                    socket.getOutputStream().write((reply+"\n").getBytes(StandardCharsets.UTF_8));
                } catch(Exception e) { if(!server.isClosed()) failure.set(e); }
            });thread.setDaemon(true);thread.start();
        }
        JsonObject status() {
            var r=object(Map.of("tick",++tick,"world","server:home-test","world_loaded",true,"on_ground",true));
            r.add("screen",menu?new JsonPrimitive("net.minecraft.client.gui.screens.inventory.ContainerScreen"):JsonNull.INSTANCE);return r;
        }
        int remaining() { return Arrays.stream(cargo).sum(); }
        JsonObject position() { return object(Map.of("x",x,"y",64,"z",z,"yaw",yaw,"pitch",pitch,"dimension","minecraft:overworld","tick",tick)); }
        JsonObject inventory() {
            var slots=new JsonArray();slots.add(object(Map.of("slot",0,"stack",Map.of("id","minecraft:diamond_pickaxe","count",1))));
            for(int i=0;i<27;i++) if(cargo[i]>0) slots.add(object(Map.of("slot",i+9,"stack",Map.of("id","minecraft:diamond","count",cargo[i]))));
            var r=new JsonObject();r.add("slots",slots);r.add("counts",object(Map.of("minecraft:diamond",remaining(),"minecraft:diamond_pickaxe",1)));return r;
        }
        JsonObject container() {
            check(menu,"Container read while closed");
            if(Arrays.stream(chest).allMatch(n->n==64)) {
                fullSnapshots++;
                if(transport && ++transportChecks>=4) { Arrays.fill(chest,0);state++; }
            }
            var slots=new JsonArray();
            for(int i=0;i<54;i++) slots.add(object(Map.of("slot",i,"inventory_slot",-1,"id","minecraft:diamond","count",chest[i],"max_count",64)));
            for(int i=0;i<27;i++) slots.add(object(Map.of("slot",54+i,"inventory_slot",9+i,"id","minecraft:diamond","count",cargo[i],"max_count",64)));
            var r=object(Map.of("container_id",7,"state_id",state,"size",54,"cursor_empty",true));r.add("slots",slots);return r;
        }
        JsonElement handle(JsonObject req) {
            String command=req.get("command").getAsString();
            return switch(command) {
                case "ping" -> object(Map.of("protocol",1));
                case "status","input.reset","key.up","mouse.up" -> status();
                case "position" -> {
                    if(pending>0 && --pending==0) { x=warehousePending?(west?99.5:100.5):.5;z=warehousePending?(west?.5:2.5):.5; }
                    yield position();
                }
                case "observe" -> {
                    boolean recovering=wounded && x>90 && remaining()==0 && !menu;
                    if(recovering)recoveryPolls++;
                    int health=wounded?(recoveryPolls>=2?20:14):20;
                    int food=wounded?(recoveryPolls>=4?20:10):20;
                    var r=object(Map.of("active",!menu,"dimension","minecraft:overworld","player",Map.of("x",x,"y",64,"z",z,"alive",true,"health",health,"food",food,"game_mode","survival"),
                            "main_hand",Map.of("id","minecraft:diamond_pickaxe","max_damage",1561,"remaining_durability",1500),
                            "target",Map.of("kind","block","id","minecraft:chest","pos",Map.of("x",100,"y",64,"z",0),"face","south","distance",2.5)));
                    if(x<90) r.add("target",JsonNull.INSTANCE);
                    yield r;
                }
                case "chat.command" -> {
                    String text=req.get("text").getAsString();commands.add(text);
                    check(Set.of("delhome kuang","sethome kuang","home home","home kuang").contains(text),"Unexpected command: "+text);
                    if(text.startsWith("home ")) { warehousePending=text.equals("home home");pending=3; }
                    yield object(Map.of("sent",true));
                }
                case "inventory" -> inventory();
                case "region.query" -> {
                    check(req.getAsJsonArray("from").get(0).getAsInt()==100,"Attempted a different chest");
                    check(req.getAsJsonArray("to").equals(req.getAsJsonArray("from")),"Guessed the other chest half");
                    yield object(Map.of("blueprint",west?"100,64,0 minecraft:chest facing=west type=left\n":"100,64,0 minecraft:chest facing=south type=right\n"));
                }
                case "mouse.move" -> { yaw+=req.get("dx").getAsDouble()*.15;pitch+=req.get("dy").getAsDouble()*.15;yield status(); }
                case "mouse.click" -> { check(req.get("button").getAsString().equals("right"),"Unexpected mining during unload");menu=true;opens++;yield status(); }
                case "wait.screen" -> { check(req.get("screen").getAsString().equals(menu?"ContainerScreen":"none"),"Wrong menu");yield status(); }
                case "container" -> container();
                case "container.deposit" -> {
                    if(staleFailures>0) {staleFailures--;state++;throw new dev.minecontrol.sdk.CommandException("STALE_CONTAINER","simulated");}
                    if(noProgress>0) {noProgress--;yield container();}
                    check(req.get("state_id").getAsInt()==state && req.get("container_id").getAsInt()==7,"Stale container move");
                    int slot=req.get("slot").getAsInt()-54;check(slot>=0 && slot<27,"Moved protected slot");
                    for(int i=0;i<54 && cargo[slot]>0;i++) { int n=Math.min(64-chest[i],cargo[slot]);chest[i]+=n;cargo[slot]-=n;if(n>0)state++; }
                    yield container();
                }
                case "container.close" -> { menu=false;yield status(); }
                default -> throw new AssertionError("Unexpected game action: "+command);
            };
        }
        public void close() throws Exception { server.close();thread.join(1000);if(failure.get()!=null)throw new AssertionError(failure.get()); }
    }
    static void scenario(int capacity,boolean full,int remaining) throws Exception { scenario(capacity,full,remaining,false); }
    static void scenario(int capacity,boolean full,int remaining,boolean west) throws Exception { scenario(capacity,full,remaining,west,false); }
    static void scenario(int capacity,boolean full,int remaining,boolean west,boolean retryFaults) throws Exception {scenario(capacity,full,remaining,west,retryFaults,false);}
    static void scenario(int capacity,boolean full,int remaining,boolean west,boolean retryFaults,boolean wounded) throws Exception {
        check(!Files.exists(FishboneMining.STORAGE_PENDING),"Existing pending state; refusing overwrite");
        try(var world=new World(capacity,west);var mc=MineControl.connect(world.server.getLocalPort())) {
            if(retryFaults) {world.staleFailures=3;world.noProgress=85;}
            world.wounded=wounded;
            if(wounded)Arrays.fill(world.cargo,0);
            world.transport=full;
            var miner=new FishboneMining(mc);miner.dimension="minecraft:overworld";
            if(wounded)miner.lastHealth=20.0;
            if(wounded)miner.guard();else miner.unloadIfNeeded();
            if(full && capacity<25)check(world.fullSnapshots>=4,"Full box did not wait for transport");
            if(wounded)check(world.recoveryPolls>=4,"Returned before both health and food recovered");
            if(retryFaults)check(world.staleFailures==0 && world.noProgress==0,"Did not retry transient failures");
            check(world.opens==1,"Switched to another chest");check(world.remaining()==0,"Wrong remaining cargo");
            check(world.commands.equals(List.of("delhome kuang","sethome kuang","home home","home kuang")),"Wrong home command sequence: "+world.commands);
            check(!Files.exists(FishboneMining.STORAGE_PENDING),"Wrong pending return state");
            check(Math.abs(world.x-.5)<.01,"Wrong final location");
            System.out.println("PASS single chest capacity="+capacity+", full="+full+", remaining="+world.remaining()+", commands="+world.commands);
        } finally { Files.deleteIfExists(FishboneMining.STORAGE_PENDING); }
    }
    static void resumeInterruptedUnload() throws Exception {
        check(!Files.exists(FishboneMining.STORAGE_PENDING),"Existing pending state; refusing overwrite");
        try(var world=new World(3456,false);var mc=MineControl.connect(world.server.getLocalPort())) {
            var saved=new dev.minecontrol.sdk.Snapshots.Position(.5,64,.5,null,0,0,"minecraft:overworld",0);
            Files.writeString(FishboneMining.STORAGE_PENDING,JSON.toJson(new FishboneMining.StorageReturn("server:home-test",saved)));
            var miner=new FishboneMining(mc);miner.inStorage=true;miner.dimension="minecraft:overworld";
            miner.recoverStorageReturn();
            check(world.remaining()==0,"Resume skipped unfinished cargo");
            check(world.commands.equals(List.of("home home","home kuang")),"Resume changed mining home");
            check(!Files.exists(FishboneMining.STORAGE_PENDING),"Resume did not confirm return");
            check(world.opens==1,"Resume selected another chest");
            System.out.println("PASS interrupted unload returns to front chest and empties cargo first");
        } finally {Files.deleteIfExists(FishboneMining.STORAGE_PENDING);}
    }
    public static void main(String[] args) throws Exception {
        check(FishboneMining.injuryUnload(20.0,19),"Damage above ten did not trigger");
        check(FishboneMining.injuryUnload(null,9),"Initially below ten did not trigger");
        check(!FishboneMining.injuryUnload(null,10),"Ten incorrectly below threshold");
        check(!FishboneMining.injuryUnload(null,14),"Initially injured above ten incorrectly triggered");
        check(!FishboneMining.injuryUnload(14.0,14),"Stable health incorrectly triggered");
        check(!FishboneMining.injuryUnload(14.0,15),"Healing incorrectly triggered");
        scenario(3456,false,0,true); // facing west, home stands west of the configured half
        scenario(3456,false,0); // unload and return
        scenario(3456,false,0,false,false,true); // injury forces warehouse trip with empty backpack, then waits for both recovery values
        scenario(3456,false,0,false,true); // stale snapshots and repeated no-progress must not end unload
        scenario(0,true,25);   // already full, transport later frees space
        scenario(1,true,24);   // fills partway, transport frees space
        resumeInterruptedUnload();
        scenario(25,true,0);   // exact fill with empty cargo can recover and return
    }
}
