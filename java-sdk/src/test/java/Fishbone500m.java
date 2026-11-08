import dev.minecontrol.sdk.MineControl;
import dev.minecontrol.sdk.Snapshots.Observation;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Dedicated test harness. Uses the production miner; counts only unique main-route edges. */
public class Fishbone500m extends FishboneMining {
    final Path statePath=Path.of(".500m-state.json");
    final JsonObject state;
    final Set<String> edges=new LinkedHashSet<>();
    boolean mainPhase,orePhase;
    String lastReason="运行中";
    long lastSave;
    Fishbone500m(MineControl mc) throws Exception {
        super(mc);
        state=Files.exists(statePath)?JsonParser.parseString(Files.readString(statePath)).getAsJsonObject():new JsonObject();
        if(state.has("edges"))for(var e:state.getAsJsonArray("edges"))edges.add(e.getAsString());
        if(!state.has("started_ms"))state.addProperty("started_ms",System.currentTimeMillis());
        state.addProperty("attempt",state.has("attempt")?state.get("attempt").getAsInt()+1:1);
        checkpoint("running");
    }
    void checkpoint(String phase) throws Exception {
        state.addProperty("phase",phase);state.addProperty("main_distance",edges.size());
        state.addProperty("updated_ms",System.currentTimeMillis());state.addProperty("reason",lastReason);
        var list=new JsonArray();edges.forEach(list::add);state.add("edges",list);
        Path temp=Path.of(".500m-state.tmp");Files.writeString(temp,new Gson().toJson(state));
        Files.move(temp,statePath,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        lastSave=System.currentTimeMillis();
    }
    @Override Observation guard() throws Exception {
        var o=super.guard();
        if(!o.mainHand().id().equals("minecraft:diamond_pickaxe"))throw new Stop("实测仅允许钻石镐");
        if(System.currentTimeMillis()-lastSave>10000)checkpoint("running");
        return o;
    }
    @Override void traceOres() throws Exception {
        boolean old=orePhase;orePhase=true;
        try{super.traceOres();}finally{orePhase=old;}
    }
    @Override boolean advance(boolean main,P branchEnd) throws Exception {
        boolean old=mainPhase;mainPhase=main;
        try{return super.advance(main,branchEnd);}finally{mainPhase=old;}
    }
    @Override void step(P q,boolean fresh) throws Exception {
        P from=foot();super.step(q,fresh);
        if(mainPhase && !orePhase && from.y()==-56 && q.y()==-56) {
            String a=from.toString(),b=q.toString();String edge=a.compareTo(b)<0?a+"|"+b:b+"|"+a;
            if(edges.add(edge)) {
                log("主矿道有效推进="+edges.size()+"/500；"+from+" -> "+q);
                var anchor=new JsonObject();anchor.addProperty("x",q.x());anchor.addProperty("y",q.y());anchor.addProperty("z",q.z());
                state.add("last_main_position",anchor);state.addProperty("last_main_heading",heading);checkpoint("running");
                if(edges.size()>=500)throw new Stop("主矿道达到500米");
            }
        }
    }
    @Override void log(String text) {
        super.log(text);
        if(text.startsWith("停止：") || text.startsWith("异常停止："))lastReason=text;
    }
    public static void main(String[] args) throws Exception {
        try(var mc=MineControl.connect(args.length==0?25580:Integer.parseInt(args[0]),MineControl.MacroRole.EXECUTION,"自动挖矿500米实测")) {
            var miner=new Fishbone500m(mc);
            try{
                if(miner.edges.size()<500) {
                    if(miner.foot().y()!=-56)throw new Stop("起点必须在Y=-56");
                    miner.run();
                }
                mc.exitReason(miner.edges.size()>=500?"主矿道达到500米":miner.lastReason);
            } catch(Exception error) {
                miner.lastReason="实测启动/执行失败："+error;
                mc.exitReason(miner.lastReason);
                throw error;
            } finally {
                miner.save();miner.checkpoint(miner.edges.size()>=500?"complete":"stopped");
            }
        }
    }
}
