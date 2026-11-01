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
        protocolPolicy();
        System.out.println("Fishbone algorithms passed: waterlogged, 3x3x6 limits, 50-block survey, shortest detour/rejoin, excavation costs, six-face adjacency, opened-space guard");
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
