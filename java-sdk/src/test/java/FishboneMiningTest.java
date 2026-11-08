import java.util.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import com.google.gson.*;
import dev.minecontrol.sdk.MineControl;

/** Algorithm checks independent of Minecraft; live input is checked separately. */
public class FishboneMiningTest {
    static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
    static FishboneMining.P p(int x,int y,int z) { return new FishboneMining.P(x,y,z); }
    public static void main(String[] args) throws Exception {
        var origin=p(0,10,0);
        var parsed=FishboneMining.parse("# MCBLUEPRINT v3\n## BLOCKS\n-1,10,2   minecraft:oak_slab   waterlogged=true\n");
        check(FishboneMining.liquid(parsed.get(p(-1,10,2))),"Waterlogged state lost");
        check(!FishboneMining.liquid("minecraft:stone"),"Stone classified liquid");
        var m=new FishboneMining(null);
        m.terrain.put(origin.add(1,1,6),"minecraft:lava");
        check(m.forwardLiquid(origin,new int[]{0,0,1}),"Far corner of 3x3x6 missed");
        m.terrain.clear();m.terrain.put(origin.add(2,1,3),"minecraft:lava");
        check(!m.forwardLiquid(origin,new int[]{0,0,1}),"Probe wider than three");
        m.terrain.clear();m.terrain.put(origin.add(0,1,7),"minecraft:water");
        check(!m.forwardLiquid(origin,new int[]{0,0,1}),"Probe longer than six");
        m.terrain.clear();m.terrain.put(origin.add(0,0,3),"minecraft:water");
        check(m.liquidBounded(origin,new int[]{0,0,1},8),"Bounded pool rejected");
        for(int x=0;x<=50;x++) m.terrain.put(origin.add(x,0,3),"minecraft:water");
        check(!m.liquidBounded(origin,new int[]{0,0,1},50),"50-block boundary not detected");
        var wall=new HashSet<FishboneMining.P>();
        for(int x=-1;x<=1;x++) wall.add(p(x,10,3));
        var route=FishboneMining.path(origin,q->q.equals(p(0,10,7)),q->!wall.contains(q),q->1,false,10);
        check(route!=null && route.size()==11,"Shortest same-level detour wrong");
        check(route.get(route.size()-1).equals(p(0,10,7)),"Did not rejoin original line");
        check(route.stream().allMatch(q->q.y()==10 && !wall.contains(q)),"Unsafe detour");
        check(FishboneMining.path(origin,q->q.equals(p(0,10,7)),q->false,q->1,false,10)==null,"Impossible path accepted");
        // Excavation costs should prefer an equally short open corridor.
        route=FishboneMining.path(origin,q->q.equals(p(0,10,2)),q->true,q->q.x()==0 && q.z()==1?20:1,false,5);
        check(route.size()==4 && !route.contains(p(0,10,1)),"Excavation cost ignored");
        check(FishboneMining.FACES.length==6,"Vein adjacency must be six faces");
        for(int[] face:FishboneMining.FACES) check(Math.abs(face[0])+Math.abs(face[1])+Math.abs(face[2])==1,"Diagonal vein adjacency");
        try { m.discover(origin);throw new AssertionError("Queried-air expansion accepted"); }
        catch(FishboneMining.Stop expected) { check(expected.getMessage().contains("未挖开"),"Wrong shell guard"); }
        check(FishboneMining.LOW_DURABILITY_COMMAND.isEmpty(),"Low-durability command must remain empty");
        check(FishboneMining.diggable("minecraft:gravel") && FishboneMining.diggable("minecraft:sand") && FishboneMining.diggable("minecraft:red_sand"),"Falling blocks must be clearable");
        check(!FishboneMining.falling("minecraft:stone"),"Ordinary rock treated as falling");
        check(FishboneMining.diggable("minecraft:smooth_basalt") && FishboneMining.support("minecraft:smooth_basalt"),"Geode shell blocks mining despite solid support");
        check(FishboneMining.diggable("minecraft:amethyst_block") && FishboneMining.diggable("minecraft:budding_amethyst"),"Ordinary blocks including budding amethyst must be attempted");
        m.terrain.clear();m.opened.clear();m.opened.add(origin);m.opened.add(origin.add(0,1,0));
        for(int x=-1;x<=1;x++)for(int y=-1;y<=2;y++)for(int z=-1;z<=1;z++)m.terrain.put(origin.add(x,y,z),"minecraft:stone");
        m.terrain.put(origin,"minecraft:air");m.terrain.put(origin.add(0,1,0),"minecraft:amethyst_cluster waterlogged=false");
        check(m.returnable(origin),"Regrown crystal blocked a previously opened return passage");
        check(!FishboneMining.support("minecraft:amethyst_cluster"),"Non-solid crystal accepted as bridge support");
        m.terrain.put(origin.add(0,1,0),"minecraft:budding_amethyst");
        check(m.returnable(origin),"Return route rejected removable new obstacle");
        m.terrain.put(origin.add(0,-1,0),"minecraft:gravel");
        check(m.standable(origin),"Untried gravel floor must allow safe repair planning");
        m.obstacles.add(origin.add(0,-1,0));
        check(!m.standable(origin),"Bypass/reset must exclude a floor already recorded as failed");
        m.obstacles.clear();
        returnStepRules();
        class ProtectedBlockMiner extends FishboneMining {
            boolean bypassed;
            ProtectedBlockMiner(){super(null);heading=0;terrain.put(p(0,-57,1),"minecraft:bedrock");terrain.put(p(0,-56,1),"minecraft:air");}
            @Override P foot(){return p(0,-57,0);}
            @Override void scanForward(P at,int[] direction){}
            @Override void localTerrain(P at){}
            @Override boolean bypassCave(int[] direction,P end){bypassed=true;return true;}
            @Override void step(P at,boolean fresh){throw new AssertionError("Protected block reached excavation step");}
        }
        var protectedMiner=new ProtectedBlockMiner();
        check(!protectedMiner.advance(true,null) && protectedMiner.bypassed,"Budding amethyst must trigger bypass without mining");
        miningRecoveryRules();
        var frontier=new FishboneMining(null);var junction=p(0,-57,0);
        for(int x=-3;x<=3;x++)for(int y=-2;y<=3;y++)for(int z=-3;z<=3;z++)frontier.terrain.put(junction.add(x,y,z),"minecraft:deepslate");
        for(int index=0;index<3;index++)frontier.opened.add(junction.add(FishboneMining.HORIZONTAL[index]));
        check(frontier.unopenedHeading(junction)==3,"Loop exit selected an already opened passage");
        frontier.opened.add(junction.add(FishboneMining.HORIZONTAL[3]));
        check(frontier.unopenedHeading(junction)==-1,"Loop exit invented unexplored space");
        var saved=new dev.minecontrol.sdk.Snapshots.Position(1826.5965,-57,-95785.0514,null,0,0,"overworld",0);
        check(FishboneMining.atMiningPoint(new dev.minecontrol.sdk.Snapshots.Position(1826.1227,-57,-95784.5076,null,0,0,"overworld",0),saved),"Home landing drift rejected");
        check(!FishboneMining.atMiningPoint(new dev.minecontrol.sdk.Snapshots.Position(1828,-57,-95785,null,0,0,"overworld",0),saved),"Remote landing accepted");
        var liveSaved=new dev.minecontrol.sdk.Snapshots.Position(1526.499923,-57,-96252.596224,null,0,0,"overworld",0);
        check(FishboneMining.atMiningPoint(new dev.minecontrol.sdk.Snapshots.Position(1526.499914,-57,-96253.630002,null,0,0,"overworld",0),liveSaved),"Actual 1.034 block settled landing rejected");
        check(!FishboneMining.atMiningPoint(new dev.minecontrol.sdk.Snapshots.Position(1526.5,-56,-96253.63,null,0,0,"overworld",0),liveSaved),"Wrong elevation accepted");
        routeRecoveryRules();
        caveAndBridgeRules();
        storageInventoryRules();
        protocolPolicy();
        System.out.println("Fishbone algorithms passed: waterlogged, 3x3x6 limits, 50-block survey, shortest detour/rejoin, excavation costs, six-face adjacency, opened-space guard");
    }
    static void miningRecoveryRules() throws Exception {
        check(FishboneMining.diggable("minecraft:oak_log _mc_hardness=2.0"),"Non-whitelisted block rejected");
        check(!FishboneMining.diggable("minecraft:custom_block _mc_hardness=-1.0"),"Unbreakable metadata ignored");
        for(String id:List.of("bedrock","reinforced_deepslate","trial_spawner","vault","chest","water","lava"))
            check(!FishboneMining.diggable("minecraft:"+id),"Must bypass "+id);
        check(!FishboneMining.support("minecraft:gravel _mc_top_full=true"),"Gravity floor accepted");
        check(!FishboneMining.support("minecraft:oak_slab _mc_top_full=false"),"Incomplete top accepted");
        check(FishboneMining.support("minecraft:oak_planks _mc_top_full=true"),"Full surface rejected");
        var miner=new FishboneMining(null);var at=p(-1,-57,-2);
        check(!miner.stationary(at,0),"Initial stall");
        check(!miner.stationary(at,FishboneMining.STATIONARY_NANOS-1),"Early stall");
        check(miner.stationary(at,FishboneMining.STATIONARY_NANOS),"3min unchanged integer position missed");
        check(!miner.stationary(at.add(1,0,0),FishboneMining.STATIONARY_NANOS+1),"Coordinate change did not reset timer");
        class RetryMiner extends FishboneMining {
            int attempts;boolean succeeds;
            RetryMiner(boolean succeeds){super(null);this.succeeds=succeeds;terrain.put(at,"minecraft:stone");}
            @Override Map<P,String> scan(P a,P b,boolean local){return terrain;}
            @Override boolean clearAttempt(P target){attempts++;if(succeeds && attempts==5){terrain.put(target,"minecraft:air");return true;}throw new Stop("挖掘超时："+target);}
            @Override void retryPause(P target){}
        }
        var success=new RetryMiner(true);check(success.clear(at) && success.attempts==5,"Fifth mining attempt must recover without exiting");
        var failure=new RetryMiner(false);
        try{failure.clear(at);throw new AssertionError("Unbounded retry");}
        catch(FishboneMining.Obstacle expected){check(failure.attempts==5 && failure.obstacles.contains(at),"Exactly5 failures must become bypass obstacle");}
    }
    static void returnStepRules() throws Exception {
        var from=p(0,-58,0);var higher=p(1,-57,0);
        class StairMiner extends FishboneMining {
            boolean repaired;int guards;
            StairMiner(){super(null);}
            @Override P foot(){return from;}
            @Override dev.minecontrol.sdk.Snapshots.Observation guard(){
                if(++guards>1)throw new Stop("movement reached");return null;
            }
            @Override void localTerrain(P point){}
            @Override void releaseWalking(){}
            @Override boolean clear(P point){terrain.put(point,"minecraft:air");return true;}
            @Override void repairUpFloor(P point){check(point.equals(higher),"Wrong return riser");repaired=true;terrain.put(point.add(0,-1,0),"minecraft:cobblestone");}
            @Override void repairFloor(P point){throw new AssertionError("Upper return used level bridge");}
        }
        var stair=new StairMiner();
        for(int x=-2;x<=3;x++)for(int y=-60;y<=-54;y++)for(int z=-2;z<=2;z++)
            stair.terrain.put(p(x,y,z),"minecraft:stone");
        stair.terrain.put(higher.add(0,-1,0),"minecraft:air");
        try{stair.step(higher,false);throw new AssertionError("Missing movement sentinel");}
        catch(FishboneMining.Stop expected){check(expected.getMessage().equals("movement reached"),"Upper return stopped before movement: "+expected.getMessage());}
        check(stair.repaired,"Upper return failed to rebuild mined floor");
    }
    static void routeRecoveryRules() {
        var original=new FishboneMining(null);
        original.routeWorld="server:test";original.dimension="overworld";original.level=-57;
        original.heading=3;original.mainHeading=2;original.mainCount=3;original.sideIndex=1;original.sideCount=4;
        original.branchAnchor=p(100,-57,-100);original.resumePoint=p(104,-57,-100);
        original.opened.add(original.resumePoint);original.opened.add(original.resumePoint.add(0,1,0));
        var decoded=FishboneMining.ROUTE_JSON.fromJson(FishboneMining.ROUTE_JSON.toJson(original.routeState()),FishboneMining.RouteState.class);
        var restored=new FishboneMining(null);restored.restoreRoute(decoded,"server:test","overworld");
        check(restored.heading==3 && restored.level==-57 && restored.mainHeading==2,"Restart lost route heading/height");
        check(restored.mainCount==3 && restored.sideIndex==1 && restored.sideCount==4,"Restart lost main/branch progress");
        check(restored.branchAnchor.equals(original.branchAnchor) && restored.resumePoint.equals(original.resumePoint)
                && restored.opened.equals(original.opened),"Restart lost return route");
        try {new FishboneMining(null).restoreRoute(decoded,"server:other","overworld");throw new AssertionError("Cross-world route reused");}
        catch(FishboneMining.Stop expected) {}
    }
    static void caveAndBridgeRules() {
        check(FishboneMining.bridgeMaterial("minecraft:cobblestone"),"Cobblestone rejected");
        check(FishboneMining.bridgeMaterial("minecraft:cobbled_deepslate"),"Cobbled deepslate rejected");
        check(!FishboneMining.bridgeMaterial("minecraft:stone"),"Unrequested bridge material accepted");
        var m=new FishboneMining(null);var start=p(0,10,0);
        var savedCorridor=new FishboneMining(null);
        for(int x=0;x<=7;x++) {savedCorridor.opened.add(p(x,10,0));savedCorridor.opened.add(p(x,11,0));}
        var recovered=savedCorridor.knownReturnRoute(p(7,10,0),p(0,10,0));
        check(recovered!=null && recovered.size()==7,"Cold terrain cache lost saved corridor");
        savedCorridor.opened.remove(p(3,11,0));
        check(savedCorridor.knownReturnRoute(p(7,10,0),p(0,10,0))==null,"Recovery authorised unknown passage");
        for(int x=-8;x<=8;x++) for(int y=8;y<=14;y++) for(int z=-8;z<=8;z++)
            m.terrain.put(p(x,y,z),"minecraft:stone");
        for(int x=-1;x<=1;x++) for(int y=10;y<=11;y++) m.terrain.put(p(x,y,1),"minecraft:cave_air");
        var route=m.caveRoute(start,new int[]{0,0,1},null,8);
        check(route!=null && route.size()>2 && route.stream().noneMatch(m::naturalCave),"Cave detour entered natural cave");
        var gap=p(0,10,1);
        check(!m.returnable(gap),"Return route cut through an avoided natural cave");
        m.opened.add(gap);m.opened.add(gap.add(0,1,0));
        check(!m.naturalCave(gap),"Existing tunnel classified cave");
        m.terrain.put(gap.add(0,-1,0),"minecraft:air");
        check(!m.returnable(gap),"Unmined floor gap accepted as return repair");
        m.opened.add(gap.add(0,-1,0));
        check(m.returnable(gap),"Mined return floor cannot be repaired");
        m.terrain.put(gap.add(1,-1,0),"minecraft:lava");
        check(!m.returnable(gap),"Return repair accepted next to lava");
    }
    static void storageInventoryRules() {
        var item=new dev.minecontrol.sdk.Snapshots.Item("minecraft:diamond",64,0,0,0,null,null,null);
        var slots=new ArrayList<dev.minecontrol.sdk.Snapshots.Slot>();
        slots.add(new dev.minecontrol.sdk.Snapshots.Slot(0,item));
        slots.add(new dev.minecontrol.sdk.Snapshots.Slot(40,item));
        var bag=new dev.minecontrol.sdk.Snapshots.Inventory(0,null,slots,Map.of());
        check(FishboneMining.emptyInventory(bag)==27 && FishboneMining.cargoCount(bag)==0,"Hotbar/offhand treated as cargo");
        for(int i=9;i<36;i++) slots.add(new dev.minecontrol.sdk.Snapshots.Slot(i,item));
        check(FishboneMining.emptyInventory(bag)==0 && FishboneMining.cargoCount(bag)==27*64,"Full main bag not detected");
    }
    static void protocolPolicy() throws Exception {
        var remaining=new java.util.concurrent.atomic.AtomicInteger(50);
        var commands=new java.util.concurrent.atomic.AtomicInteger();
        var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        try(var server=new ServerSocket(0,10,InetAddress.getLoopbackAddress())) {
            Thread worker=new Thread(()-> {
                try {
                    while(!server.isClosed()) try(var socket=server.accept()) {
                        var reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
                        var request=JsonParser.parseString(reader.readLine()).getAsJsonObject();
                        String command=request.get("command").getAsString();
                        var result=new JsonObject();
                        if(command.equals("ping")) result.addProperty("protocol",1);
                        if(command.equals("chat.command")) commands.incrementAndGet();
                        if(command.equals("observe")) {
                            result=JsonParser.parseString("{\"active\":true,\"dimension\":\"minecraft:overworld\",\"player\":{\"alive\":true,\"health\":20,\"food\":20,\"game_mode\":\"survival\"},\"main_hand\":{\"id\":\"minecraft:diamond_pickaxe\",\"max_damage\":1561}}").getAsJsonObject();
                            result.getAsJsonObject("main_hand").addProperty("remaining_durability",remaining.get());
                        }
                        if(command.equals("region.query")) result.addProperty("blueprint",
                                "0,11,0 minecraft:diamond_ore\n1,10,0 minecraft:iron_ore\n1,11,1 minecraft:emerald_ore\n");
                        var reply=new JsonObject();reply.add("id",request.get("id"));reply.addProperty("ok",true);reply.add("result",result);
                        socket.getOutputStream().write((reply+"\n").getBytes(StandardCharsets.UTF_8));
                    }
                } catch(Exception e) { if(!server.isClosed()) failure.set(e); }
            });
            worker.start();
            try(var mc=MineControl.connect(server.getLocalPort())) {
                var m=new FishboneMining(mc);var origin=p(0,10,0);
                m.guard();check(!m.lowTriggered,"Exactly 50 must continue");
                m.scan(origin.add(-2,-2,-2),origin.add(2,2,2),false);
                check(m.oreCandidates.isEmpty(),"Broad survey leaked ore targets");
                check(m.at(p(0,11,0)).equals("minecraft:stone"),"Broad survey retained ore identity");
                m.opened.add(origin);m.discover(origin);
                check(m.oreCandidates.contains(p(0,11,0)) && m.oreCandidates.contains(p(1,10,0)),"Face ores missed");
                check(!m.oreCandidates.contains(p(1,11,1)),"Corner ore accepted");
                remaining.set(49);
                for(int i=0;i<2;i++) try { m.guard();throw new AssertionError("49 did not stop"); }
                catch(FishboneMining.Stop expected) { }
                check(m.lowTriggered && commands.get()==0,"Empty hook sent a command");
                check(m.audit.stream().filter(s->s.startsWith("低耐久触发")).count()==1,"Low durability triggered twice");
            } finally { server.close();worker.join(2000); }
            if(failure.get()!=null) throw new AssertionError(failure.get());
        }
    }
}
