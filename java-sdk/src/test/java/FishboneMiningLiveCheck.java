import dev.minecontrol.sdk.MineControl;
import java.nio.file.*;

/** Opt-in checks in the dedicated local test world, never an external server. */
public class FishboneMiningLiveCheck {
    static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
    static FishboneMining start(MineControl mc) throws Exception {
        var m=new FishboneMining(mc);m.dimension=mc.observe().dimension();m.level=m.foot().y();m.heading=0;
        m.opened.add(m.foot());m.opened.add(m.foot().add(0,1,0));m.localTerrain(m.foot());return m;
    }
    public static void main(String[] args) throws Exception {
        try(var mc=MineControl.connect(Integer.parseInt(args[0]))) {
            String mode=args.length>1?args[1]:Files.readString(Path.of("fishbone-live-scenario.txt")).trim();
            if(mode.startsWith("bridge") || mode.equals("cave")) mc.register(MineControl.MacroRole.EXECUTION,"坑洞跨越模拟测试："+mode);
            var m=mode.equals("low")?new FishboneMining(mc):start(mc);
            try {
                if(mode.startsWith("bridge") || mode.equals("cave")) {
                    var spec=com.google.gson.JsonParser.parseString(Files.readString(Path.of("mobility-case.json"))).getAsJsonObject();
                    var origin=m.foot();int heading=spec.get("heading").getAsInt();m.heading=heading;
                    int distance=spec.get("distance").getAsInt();int[] direction=FishboneMining.HORIZONTAL[heading];
                    var destination=origin.along(direction,distance);
                    if(mode.equals("bridge-return-step")) {
                        var higher=origin.along(direction,1).add(0,1,0);
                        m.opened.add(origin);m.opened.add(origin.add(0,1,0));
                        m.opened.add(higher);m.opened.add(higher.add(0,1,0));m.opened.add(higher.add(0,-1,0));
                        m.routeWorld=mc.call("status",new com.google.gson.JsonObject()).getAsJsonObject().get("world").getAsString();
                        m.resumePoint=higher;m.level=higher.y();m.heading=2;m.mainCount=2;m.routeReady=true;
                        m.writeRoute();
                        m.aim(origin.x()+5,origin.y()+1.62,origin.z());
                        var restored=start(mc);
                        restored.restoreRoute(FishboneMining.ROUTE_JSON.fromJson(Files.readString(FishboneMining.ROUTE_STATE),FishboneMining.RouteState.class),m.routeWorld,m.dimension);
                        restored.localTerrain(restored.foot());restored.returnTo(restored.resumePoint);
                        check(restored.foot().equals(higher),"Failed to rebuild and climb return riser");
                        check(restored.heading==2 && restored.level==higher.y() && restored.mainCount==2,"Restart used current camera instead of saved route");
                        check(restored.audit.stream().anyMatch(line->line.startsWith("修复返回台阶")),"Riser not actually placed");
                        m.audit.addAll(restored.audit);m.log("补台阶返回及转动视角后的路线恢复通过");
                    } else if(mode.equals("bridge-safe-stop")) {
                        try { m.step(origin.along(direction,1),true);throw new AssertionError("Unsafe bridge did not stop"); }
                        catch(FishboneMining.Stop expected) {
                            check(expected.getMessage().contains(spec.get("reason").getAsString()),"Wrong bridge protection: "+expected.getMessage());
                            m.log("保护停机确认："+expected.getMessage());
                        }
                        check(m.foot().equals(origin),"Protection moved player into pit");
                        check(m.audit.stream().noneMatch(line->line.startsWith("铺路填补地板")),"Protection placed a block");
                    } else if(mode.equals("bridge") || mode.equals("bridge-integrated")) {
                        var originalMaterials=mc.inventory().counts();
                        var materialTypes=originalMaterials.keySet().stream().filter(FishboneMining::bridgeMaterial).toList();
                        int materialBefore=materialTypes.stream().mapToInt(k->originalMaterials.getOrDefault(k,0)).sum();
                        for(int i=1;i<distance;i++) {
                            if(mode.equals("bridge-integrated")) check(m.advance(true,null),"Normal mining did not bridge the pit");
                            else m.step(origin.along(direction,i),true);
                        }
                        int placed=(int)m.audit.stream().filter(line->line.startsWith("铺路填补地板")).count();
                        check(placed==spec.get("placements").getAsInt(),"Wrong number of bridge blocks: "+placed);
                        var remainingMaterials=mc.inventory().counts();
                        int materialAfter=materialTypes.stream().mapToInt(k->remainingMaterials.getOrDefault(k,0)).sum();
                        check(materialBefore-materialAfter==placed,"Bridge block consumption mismatch: "+materialBefore+" -> "+materialAfter+", placed="+placed);
                        if(mode.equals("bridge-integrated")) check(m.advance(true,null),"Normal mining did not reach landing");
                        else m.step(destination,true);
                        check(m.foot().equals(destination),"Bridge did not reach opposite bank");
                        m.returnTo(origin);check(m.foot().equals(origin),"Cannot return over bridge");
                    } else {
                        check(!m.advance(true,null),"Natural cave was not bypassed");
                        check(m.foot().y()==origin.y() && (m.foot().x()-origin.x())*direction[2]-(m.foot().z()-origin.z())*direction[0]==0,"Cave route did not rejoin original line");
                        check(m.audit.stream().anyMatch(line->line.startsWith("绕天然矿洞：")),"No actual cave detour");
                        check(m.audit.stream().noneMatch(line->line.startsWith("铺路填补地板")),"Cave detour unexpectedly bridged");
                        m.returnTo(origin);check(m.foot().equals(origin),"Cannot return over cave detour");
                    }
                } else if(mode.equals("storage")) {
                    var origin=mc.position();var before=mc.inventory();
                    check(FishboneMining.emptyInventory(before)<=2,"Storage test bag is not full");
                    m.unloadIfNeeded();
                    check(FishboneMining.cargoCount(mc.inventory())==0,"Main inventory not emptied");
                    var after=mc.position();
                    check(Math.abs(after.x()-origin.x())<.2 && Math.abs(after.y()-origin.y())<.2 && Math.abs(after.z()-origin.z())<.2,"Did not return to mining position");
                    for(var slot:before.slots()) if(slot.slot()<9 || slot.slot()>=36) {
                        var retained=mc.inventory().slots().stream().filter(s->s.slot()==slot.slot()).findFirst().orElseThrow();
                        check(retained.stack().id().equals(slot.stack().id()) && retained.stack().count()==slot.stack().count(),"Retained slot changed: "+slot.slot());
                    }
                    check(!Files.exists(FishboneMining.STORAGE_PENDING),"Unload marker remains");
                    m.clear(m.foot().add(0,0,1));
                    check(m.audit.stream().anyMatch(s->s.startsWith("挖除")),"Mining did not resume after unloading");
                } else if(mode.equals("gravel")) {
                    var origin=m.foot();
                    check(m.advance(true,null),"Gravel blocked advance");
                    check(m.foot().equals(origin.add(0,0,1)),"Did not advance after gravel settled");
                    var column=m.scan(origin.add(0,0,1),origin.add(0,7,1),false);
                    check(column.values().stream().noneMatch(FishboneMining::falling),"Falling gravel remains in test column");
                    check(m.audit.stream().anyMatch(line->line.startsWith("落沙清理完成")),"No settling confirmation");
                } else if(mode.equals("pit")) {
                    var origin=m.foot();
                    for(int i=0;i<3;i++) check(m.advance(true,null),"Pit bridge failed");
                    check(m.foot().y()==origin.y() && m.foot().x()==origin.x() && m.foot().z()>=origin.z()+2,"Pit bypass failed to rejoin");
                    check(m.audit.stream().anyMatch(line->line.startsWith("铺路填补地板")),"Pit bridge was not used");
                } else if(mode.equals("cycle")) {
                    var origin=m.foot();m.traceOres();
                    for(int i=0;i<3;i++) check(m.advance(true,null),"Unexpected main reroute");
                    var anchor=m.foot();
                    check(m.minedOres.contains(origin.add(1,0,3)) && m.minedOres.contains(origin.add(2,0,3)),"Connected vein incomplete");
                    check(!m.minedOres.contains(origin.add(3,1,4)),"Diagonal-only vein incorrectly traced");
                    for(int heading:new int[]{3,1}) {
                        m.heading=heading;
                        for(int i=0;i<5;i++) check(m.advance(false,anchor.along(FishboneMining.HORIZONTAL[heading],5)),"Unexpected branch reroute");
                        m.returnTo(anchor);
                    }
                    check(m.foot().equals(origin.add(0,0,3)),"Cycle did not return to anchor");
                } else if(mode.equals("liquid")) {
                    var origin=m.foot();m.scanForward(origin,FishboneMining.HORIZONTAL[0]);
                    check(m.forwardLiquid(origin,FishboneMining.HORIZONTAL[0]),"Pool not detected");
                    check(m.bypass(FishboneMining.HORIZONTAL[0],null),"Pool could not be bypassed");
                    check(m.foot().x()==origin.x() && m.foot().y()==origin.y() && m.foot().z()>origin.z()+5,"Wrong rejoin line");
                } else if(mode.equals("large")) {
                    var origin=m.foot();
                    check(!m.advance(true,null) && m.heading!=0,"Large pool failed to turn");
                    check(m.foot().equals(origin),"Turn moved into liquid");
                    check(m.advance(true,null),"Cannot mine after turn");
                } else if(mode.equals("low")) {
                    try { m.guard();throw new AssertionError("Low durability did not stop"); }
                    catch(FishboneMining.Stop expected) { check(m.lowTriggered,"Wrong stop reason"); }
                } else if(mode.equals("repair")) {
                    var destination=m.foot().add(0,0,1);
                    m.clear(destination.add(0,1,0));m.clear(destination);m.clear(destination.add(0,-1,0));
                    m.step(destination,true);
                    check(m.foot().equals(destination) && FishboneMining.support(m.at(destination.add(0,-1,0))),"Floor repair failed");
                } else throw new IllegalArgumentException(mode);
                m.log("LIVE PASS: "+mode+" position="+m.foot()+" minedOres="+m.minedOres.size());
            } finally {
                Files.createDirectories(Path.of("docs/fishbone-java-test"));
                Files.write(Path.of("docs/fishbone-java-test/"+mode+".log"),m.audit);
            }
        }
    }
}
