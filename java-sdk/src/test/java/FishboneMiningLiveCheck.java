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
            var m=mode.equals("low")?new FishboneMining(mc):start(mc);
            try {
                if(mode.equals("gravel")) {
                    var origin=m.foot();
                    check(m.advance(true,null),"Gravel blocked advance");
                    check(m.foot().equals(origin.add(0,0,1)),"Did not advance after gravel settled");
                    var column=m.scan(origin.add(0,0,1),origin.add(0,7,1),false);
                    check(column.values().stream().noneMatch(FishboneMining::falling),"Falling gravel remains in test column");
                    check(m.audit.stream().anyMatch(line->line.startsWith("落沙清理完成")),"No settling confirmation");
                } else if(mode.equals("pit")) {
                    var origin=m.foot();
                    check(!m.advance(true,null),"Pit bypass must reset the main counter");
                    check(m.foot().y()==origin.y() && m.foot().x()==origin.x() && m.foot().z()>=origin.z()+2,"Pit bypass failed to rejoin");
                    check(m.audit.stream().anyMatch(line->line.startsWith("绕坑：")),"Pit bypass was not used");
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
