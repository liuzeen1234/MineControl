import dev.minecontrol.sdk.MineControl;
import dev.minecontrol.sdk.Snapshots.*;
import java.util.*;
import java.util.function.Predicate;
import java.nio.file.*;
import static dev.minecontrol.sdk.MineControl.MouseButton.LEFT;

/** F8 single-source macro. No Minecraft classes or external Python process required. */
public class FishboneMining {
    // Leave empty until the desired command is supplied. Trigger once, then exit.
    static final String LOW_DURABILITY_COMMAND = "";
    static final double DEGREES_PER_MOUSE = .15; // sensitivity 0.5, invert Y off
    static final int LIQUID_RADIUS = 50;
    static final int[][] FACES = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    static final int[][] HORIZONTAL = {{0,0,1},{-1,0,0},{0,0,-1},{1,0,0}};
    static final Set<String> ROCK = Set.of("stone", "deepslate", "granite", "diorite", "andesite",
            "tuff", "calcite", "dirt", "grass_block", "cobblestone", "cobbled_deepslate",
            "netherrack", "sandstone", "red_sandstone", "clay", "end_stone");
    record P(int x, int y, int z) {
        P add(int x, int y, int z) { return new P(this.x+x,this.y+y,this.z+z); }
        P add(int[] d) { return add(d[0],d[1],d[2]); }
        P along(int[] d, int n) { return add(d[0]*n,d[1]*n,d[2]*n); }
        BlockPos block() { return new BlockPos(x,y,z); }
        int distance(P b) { return Math.abs(x-b.x)+Math.abs(y-b.y)+Math.abs(z-b.z); }
    }
    static final class Stop extends RuntimeException { Stop(String reason) { super(reason); } }
    final MineControl mc;
    final Map<P,String> terrain = new HashMap<>();
    final Set<P> opened = new HashSet<>();
    final Set<P> oreCandidates = new LinkedHashSet<>();
    final Set<P> minedOres = new HashSet<>();
    final List<String> audit = new ArrayList<>();
    String dimension;
    int level, heading;
    boolean lowTriggered;
    boolean branchNeedsTurn;
    FishboneMining(MineControl mc) { this.mc = mc; }

    public static void main(String[] args) throws Exception {
        try (var mc = MineControl.connect(args.length == 0 ? 25580 : Integer.parseInt(args[0]))) {
            var miner = new FishboneMining(mc);
            try { miner.run(); }
            finally { miner.save(); }
        }
    }
    void log(String text) {
        if(audit.size()>=10000) audit.subList(0,1000).clear();
        audit.add(text); System.out.println(text);
    }
    void save() throws Exception {
        Path path = Path.of("fishbone-last-run.log");
        Files.write(path, audit);
        System.out.println("鱼骨宏日志：" + path.toAbsolutePath());
    }
    Observation guard() throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        var o = mc.observe();
        if (!o.active() || o.player() == null || !o.player().alive()) throw new Stop("世界或界面不可用");
        if (dimension != null && !dimension.equals(o.dimension())) throw new Stop("维度改变");
        if (!"survival".equals(o.player().gameMode())) throw new Stop("需要生存模式");
        if (o.player().health()<16 || o.player().food()<6) throw new Stop("生命或饥饿保护");
        var hand = o.mainHand();
        if (hand == null || !hand.id().endsWith("_pickaxe") || hand.maxDamage()<=0)
            throw new Stop("主手需要有耐久的镐");
        if (hand.remainingDurability()<50) {
            if (!lowTriggered) {
                lowTriggered = true;
                mc.mouse().up(LEFT); mc.key().up("w"); mc.key().up("space");
                if (!LOW_DURABILITY_COMMAND.isBlank()) {
                    var command=new com.google.gson.JsonObject();
                    command.addProperty("text",LOW_DURABILITY_COMMAND);
                    mc.call("chat.command",command);
                }
                log("低耐久触发："+hand.remainingDurability()+"；"+
                        (LOW_DURABILITY_COMMAND.isBlank()?"指令尚未配置，已停止":"已发送指令并停止"));
            }
            throw new Stop("剩余耐久低于50");
        }
        return o;
    }
    P foot() throws Exception {
        var p=mc.position();
        return new P((int)Math.floor(p.x()+1e-5),(int)Math.floor(p.y()+1e-5),(int)Math.floor(p.z()+1e-5));
    }
    static String id(String state) { return state == null ? "?" : state.split("\\s+")[0]; }
    static boolean ore(String state) {
        String s=id(state);
        return s.startsWith("minecraft:") && (s.endsWith("_ore") ||
                Set.of("minecraft:ancient_debris","minecraft:raw_iron_block","minecraft:raw_copper_block","minecraft:raw_gold_block").contains(s));
    }
    static boolean liquid(String state) {
        return id(state).equals("minecraft:water") || id(state).equals("minecraft:lava") ||
                (state != null && state.contains("waterlogged=true"));
    }
    static boolean air(String s) { return id(s).equals("minecraft:air") || id(s).equals("minecraft:cave_air"); }
    static boolean diggable(String s) { return air(s) || ore(s) || ROCK.contains(id(s).replace("minecraft:","")); }
    static boolean support(String s) { return !air(s) && (diggable(s) || id(s).equals("minecraft:bedrock")); }
    static Map<P,String> parse(String blueprint) {
        var result = new HashMap<P,String>();
        for (String line:blueprint.split("\\R")) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] a=line.trim().split("\\s+",2), xyz=a[0].split(",");
            if (xyz.length==3 && a.length==2)
                result.put(new P(Integer.parseInt(xyz[0]),Integer.parseInt(xyz[1]),Integer.parseInt(xyz[2])),a[1]);
        }
        return result;
    }
    // Broad scans discard ALL ore identities. They cannot populate the target queue.
    Map<P,String> scan(P a,P b,boolean local) throws Exception {
        guard();
        var blocks=parse(mc.region(a.block(),b.block()).blueprint());
        for (int x=Math.min(a.x,b.x);x<=Math.max(a.x,b.x);x++)
            for (int y=Math.min(a.y,b.y);y<=Math.max(a.y,b.y);y++)
                for (int z=Math.min(a.z,b.z);z<=Math.max(a.z,b.z);z++) {
                    P p=new P(x,y,z);String s=blocks.getOrDefault(p,"minecraft:air");
                    terrain.put(p,!local && ore(s)?"minecraft:stone":s);
                }
        return blocks;
    }
    String at(P p) { return terrain.get(p); }
    boolean safeCell(P p) {
        for (int x=-1;x<=1;x++) for (int y=-1;y<=1;y++) for (int z=-1;z<=1;z++) {
            String s=at(p.add(x,y,z));
            if (s==null || liquid(s)) return false;
        }
        return true;
    }
    boolean standable(P p) {
        return support(at(p.add(0,-1,0))) && diggable(at(p)) && diggable(at(p.add(0,1,0)))
                && safeCell(p) && safeCell(p.add(0,1,0));
    }
    void scanForward(P p,int[] d) throws Exception {
        // Three wide, three high (feet..ceiling), six ahead, exact 54 cells.
        int[] side={d[2],0,-d[0]};
        scan(p.along(d,1).along(side,-1),p.along(d,6).along(side,1).add(0,2,0),false);
        log("预探测 3x3x6："+p+" 方向="+Arrays.toString(d));
    }
    boolean forwardLiquid(P p,int[] d) {
        int[] side={d[2],0,-d[0]};
        for(int n=1;n<=6;n++) for(int w=-1;w<=1;w++) for(int h=0;h<3;h++)
            if(liquid(at(p.along(d,n).along(side,w).add(0,h,0)))) return true;
        return false;
    }
    void localTerrain(P p) throws Exception { scan(p.add(-2,-2,-2),p.add(2,3,2),false); }
    void discover(P cell) throws Exception {
        // A search origin MUST have actually been opened, never merely queried air.
        if (!opened.contains(cell)) throw new Stop("拒绝从未挖开的位置扩展矿石查询");
        var blocks=scan(cell.add(-1,-1,-1),cell.add(1,1,1),true);
        // Only face-adjacent ores join this vein; corners are deliberately excluded.
        for (int[] d:FACES) {
            P q=cell.add(d);
            if (ore(blocks.get(q)) && !minedOres.contains(q)) oreCandidates.add(q);
        }
    }
    void aim(double x,double y,double z) throws Exception {
        for(int i=0;i<4;i++) {
            guard(); var p=mc.position();
            double dx=x-p.x(),dy=y-p.y()-1.62,dz=z-p.z();
            double yaw=Math.toDegrees(Math.atan2(-dx,dz));
            double pitch=-Math.toDegrees(Math.atan2(dy,Math.hypot(dx,dz)));
            double turn=((yaw-p.yaw()+540)%360)-180;
            if(Math.abs(turn)<.35 && Math.abs(pitch-p.pitch())<.35) return;
            mc.mouse().move(turn/DEGREES_PER_MOUSE,(pitch-p.pitch())/DEGREES_PER_MOUSE);
            mc.waitTicks(1);
        }
        var p=mc.position();
        double yaw=Math.toDegrees(Math.atan2(-(x-p.x()),z-p.z()));
        double pitch=-Math.toDegrees(Math.atan2(y-p.y()-1.62,Math.hypot(x-p.x(),z-p.z())));
        if(Math.abs(((yaw-p.yaw()+540)%360)-180)>1 || Math.abs(pitch-p.pitch())>1)
            throw new Stop("视角校准失败：需要灵敏度0.5、关闭Y轴反转");
    }
    Target sight(P q) throws Exception {
        // Aim at an exposed face rather than through another solid block.
        var p=mc.position();
        var faces=new ArrayList<int[]>(Arrays.asList(FACES));
        faces.sort(Comparator.comparingDouble(d -> Math.pow(q.x+.5+d[0]*.499-p.x(),2)+
                Math.pow(q.y+.5+d[1]*.499-p.y()-1.62,2)+Math.pow(q.z+.5+d[2]*.499-p.z(),2)));
        for(int[] d:faces) {
            if(!air(at(q.add(d)))) continue;
            aim(q.x+.5+d[0]*.499,q.y+.5+d[1]*.499,q.z+.5+d[2]*.499);
            var t=guard().target();
            if(t!=null && "block".equals(t.kind()) && q.block().equals(t.pos()) && t.distance()<=4.3) return t;
        }
        return null;
    }
    boolean clear(P q) throws Exception {
        guard();
        scan(q,q,false);
        if(air(at(q))) { opened.add(q);return false; }
        if(q.equals(foot().add(0,-1,0))) throw new Stop("拒绝挖除自身脚下支撑；矿脉未完成");
        scan(q.add(-1,-1,-1),q.add(1,1,1),false);
        if(!safeCell(q)) throw new Stop("开挖位置邻近液体或未知边界；矿脉可能未完成");
        Target t=sight(q);
        if(t==null || !diggable(t.id())) throw new Stop("方块不可见或不支持开挖："+q);
        boolean isOre=ore(t.id());String expected=t.id();
        long end=System.nanoTime()+12_000_000_000L;
        try {
            // Keep progress continuous, with a finite expiry and a wall-clock timeout.
            mc.mouse().hold(LEFT,300);
            while(true) {
                guard();
                scan(q,q,false);
                if(air(at(q))) break;
                var current=guard().target();
                if(current==null || !q.block().equals(current.pos()) || !expected.equals(current.id())) {
                    // The block can break between the region sample and crosshair sample.
                    mc.mouse().up(LEFT);
                    scan(q,q,false);
                    if(air(at(q))) break;
                    throw new Stop("开挖期间目标变化："+q);
                }
                if(System.nanoTime()>end) throw new Stop("挖掘超时："+q);
            }
        } finally { mc.mouse().up(LEFT); }
        opened.add(q);
        if(isOre) { minedOres.add(q);oreCandidates.remove(q);discover(q); }
        log("挖除 "+q+" "+expected);
        return true;
    }
    void repairFloor(P destination) throws Exception {
        P origin=foot(),floor=destination.add(0,-1,0),anchor=origin.add(0,-1,0);
        if(!air(at(floor)) || !opened.contains(floor) || origin.y!=destination.y || !support(at(anchor)))
            throw new Stop("没有安全落脚面，且不是追矿挖出的可修复地板");
        var inventory=mc.inventory();String tool=guard().mainHand().id();
        var material=inventory.slots().stream().filter(s->s.slot()>=0 && s.slot()<9 && s.stack()!=null &&
                s.stack().count()>0 && ROCK.contains(id(s.stack().id()).replace("minecraft:",""))).findFirst();
        var pick=inventory.slots().stream().filter(s->s.slot()>=0 && s.slot()<9 && s.stack()!=null && tool.equals(s.stack().id())).findFirst();
        if(material.isEmpty() || pick.isEmpty()) throw new Stop("追矿地板修复需要快捷栏中的实心方块与镐");
        int dx=destination.x-origin.x,dz=destination.z-origin.z;
        try {
            mc.key().hold("left_shift",200);
            long end=System.nanoTime()+5_000_000_000L;
            while(true) {
                guard();var p=mc.position();
                if(Math.abs(p.y()-origin.y)>.2 || System.nanoTime()>end) throw new Stop("地板修复靠近边缘失败");
                if((p.x()-origin.x-.5)*dx+(p.z()-origin.z-.5)*dz>=.65) break;
                aim(destination.x+.5,p.y()+1.62,destination.z+.5);
                mc.key().holdAndWait("w",1);mc.key().up("w");
            }
            // Sneaking eyes are 0.35 lower than aim()'s standing-eye reference.
            aim(anchor.x+.5+dx*.499,anchor.y+.5+.35,anchor.z+.5+dz*.499);
            var target=guard().target();
            String face=dx>0?"east":dx<0?"west":dz>0?"south":"north";
            if(target==null || !anchor.block().equals(target.pos()) || !face.equals(target.face()))
                throw new Stop("无法看见修复地板的放置面");
            mc.key().tap(Integer.toString(material.get().slot()+1));
            mc.mouse().click(MineControl.MouseButton.RIGHT);mc.waitTicks(3);
            mc.key().tap(Integer.toString(pick.get().slot()+1));
            scan(floor,floor,false);
            if(!support(at(floor))) throw new Stop("地板放置未确认");
            opened.remove(floor);log("修复追矿地板 "+floor);
        } finally {
            mc.mouse().up(MineControl.MouseButton.RIGHT);mc.key().up("w");
            mc.key().tap(Integer.toString(pick.get().slot()+1));mc.key().up("left_shift");
        }
    }
    void step(P q,boolean fresh) throws Exception {
        guard();P from=foot();
        if(Math.abs(from.x-q.x)+Math.abs(from.z-q.z)!=1 || Math.abs(from.y-q.y)>1)
            throw new Stop("非相邻移动："+from+" -> "+q);
        if(fresh) scanForward(from,new int[]{q.x-from.x,0,q.z-from.z});
        localTerrain(q);
        if(!support(at(q.add(0,-1,0)))) repairFloor(q);
        if(!standable(q)) throw new Stop("目标位置无安全支撑："+q);
        if(q.y>from.y) clear(from.add(0,2,0));
        for(int y=Math.max(from.y,q.y)+1;y>=q.y;y--) clear(new P(q.x,y,q.z));
        localTerrain(q);
        if(!standable(q) || !air(at(q)) || !air(at(q.add(0,1,0)))) throw new Stop("开挖后通道不安全");
        long end=System.nanoTime()+7_000_000_000L;
        try {
            while(true) {
                guard();var p=mc.position();
                double distance=Math.hypot(p.x()-q.x-.5,p.z()-q.z-.5);
                if(distance<.18 && Math.abs(p.y()-q.y)<.15 && Boolean.TRUE.equals(mc.status().onGround())) break;
                if(System.nanoTime()>end || Math.abs(p.y()-from.y)>1.5) throw new Stop("移动超时或高度异常");
                aim(q.x+.5,p.y()+1.62,q.z+.5);
                if(q.y>p.y()+.2) { mc.key().up("space");mc.key().hold("space",5); }
                mc.key().holdAndWait("w",distance<.45?1:2);
                mc.key().up("w");
            }
        } finally { mc.key().up("w");mc.key().up("space"); }
        opened.add(q);opened.add(q.add(0,1,0));
        log("移动 "+q);
    }
    record Node(P p,int cost) {}
    // Dijkstra: one unit to walk, one per block requiring excavation. No ore preference.
    static List<P> path(P start,Predicate<P> goal,Predicate<P> allowed,
                        java.util.function.ToIntFunction<P> cost,boolean vertical,int radius) {
        var queue=new PriorityQueue<Node>(Comparator.comparingInt(Node::cost));
        var distances=new HashMap<P,Integer>();var parents=new HashMap<P,P>();
        queue.add(new Node(start,0));distances.put(start,0);
        while(!queue.isEmpty()) {
            Node n=queue.remove();P p=n.p;
            if(n.cost!=distances.get(p)) continue;
            if(goal.test(p)) {
                var result=new ArrayList<P>();
                while(!p.equals(start)) { result.add(p);p=parents.get(p); }
                Collections.reverse(result);return result;
            }
            for(int[] d:HORIZONTAL) for(int dy=vertical?-1:0;dy<=(vertical?1:0);dy++) {
                P q=p.add(d).add(0,dy,0);
                if(Math.max(Math.abs(q.x-start.x),Math.abs(q.z-start.z))>radius || Math.abs(q.y-start.y)>radius) continue;
                if(!allowed.test(q)) continue;
                int next=n.cost+cost.applyAsInt(q)+Math.abs(dy);
                if(next<distances.getOrDefault(q,Integer.MAX_VALUE)) {
                    distances.put(q,next);parents.put(q,p);queue.add(new Node(q,next));
                }
            }
        }
        return null;
    }
    int excavationCost(P p) { return 1+(air(at(p))?0:1)+(air(at(p.add(0,1,0)))?0:1); }
    void follow(List<P> path,boolean discover) throws Exception {
        if(path==null) throw new Stop("找不到安全路径，矿脉可能未完成");
        for(P p:path) {
            step(p,true);
            if(discover) { discover(p);discover(p.add(0,1,0)); }
        }
    }
    void returnTo(P anchor) throws Exception {
        // Recompute: a mined floor ore may have changed an earlier passage.
        localTerrain(foot());
        var back=path(foot(),anchor::equals,p->standable(p) && air(at(p)) && air(at(p.add(0,1,0))),p->1,true,64);
        follow(back,false);
    }
    void traceOres() throws Exception {
        P anchor=foot();
        discover(anchor);discover(anchor.add(0,1,0));
        while(!oreCandidates.isEmpty()) {
            guard();
            P target=oreCandidates.iterator().next();
            // Refresh ONLY a previously authorised immediate-neighbour target.
            var exact=scan(target,target,true).get(target);
            if(!ore(exact)) { oreCandidates.remove(target);continue; }
            localTerrain(foot());
            if(sight(target)==null || target.equals(foot().add(0,-1,0))) {
                // Expand terrain for safe stance planning, never for ore targeting.
                scan(target.add(-5,-4,-5),target.add(5,5,5),false);
                P start=foot();
                var route=path(start,p->!p.equals(start) && !target.equals(p.add(0,-1,0)) &&
                        p.distance(target)<=3 && rayClear(p,target),
                        p->standable(p) && !p.equals(target) && !p.add(0,1,0).equals(target),this::excavationCost,true,12);
                follow(route,false);
            }
            if(sight(target)==null) throw new Stop("矿脉未完成：无法安全接近 "+target);
            clear(target);
            oreCandidates.remove(target);
        }
        returnTo(anchor);
        log("当前六面相连矿脉处理完成，返回 "+anchor);
    }
    boolean rayClear(P stance,P target) {
        double x=stance.x+.5,y=stance.y+1.62,z=stance.z+.5;
        for(int[] face:FACES) {
            P neighbor=target.add(face);
            if(!air(at(neighbor)) && !neighbor.equals(stance) && !neighbor.equals(stance.add(0,1,0))) continue;
            boolean visible=true;
            for(int i=1;i<40;i++) {
                double t=i/40.0;
                P q=new P((int)Math.floor(x+(target.x+.5+face[0]*.499-x)*t),
                        (int)Math.floor(y+(target.y+.5+face[1]*.499-y)*t),
                        (int)Math.floor(z+(target.z+.5+face[2]*.499-z)*t));
                if(q.equals(target)) break;
                if(q.equals(stance) || q.equals(stance.add(0,1,0))) continue;
                if(!air(at(q))) { visible=false;break; }
            }
            if(visible) return true;
        }
        return false;
    }
    // Flood horizontal projection of liquid with a safety halo, gradually up to 50 blocks.
    Set<P> liquidComponent(P origin,int[] d,int radius) {
        var queue=new ArrayDeque<P>();var seen=new HashSet<P>();
        int[] side={d[2],0,-d[0]};
        for(int n=1;n<=6;n++) for(int w=-1;w<=1;w++) for(int h=0;h<3;h++) {
            P q=origin.along(d,n).along(side,w).add(0,h,0);
            if(liquid(at(q))) { queue.add(q);seen.add(q); }
        }
        while(!queue.isEmpty()) {
            P p=queue.remove();
            if(Math.abs(p.x-origin.x)>=radius-1 || Math.abs(p.z-origin.z)>=radius-1) return null;
            for(int[] f:FACES) {
                P q=p.add(f);
                // Only the horizontal footprint in the tunnel safety slice is relevant.
                if(q.y>=origin.y-1 && q.y<=origin.y+3 && liquid(at(q)) && seen.add(q)) queue.add(q);
            }
        }
        return seen;
    }
    boolean liquidBounded(P origin,int[] d,int radius) { return liquidComponent(origin,d,radius)!=null; }
    void survey(P origin,int radius) throws Exception {
        // Tile requests so both block-count and response-byte caps stay bounded.
        for(int x=-radius;x<=radius;x+=12) for(int z=-radius;z<=radius;z+=12)
            scan(origin.add(x,-2,z),origin.add(Math.min(radius,x+11),4,Math.min(radius,z+11)),false);
        log("液体水平勘探半径="+radius);
    }
    boolean bypass(int[] d,P branchEnd) throws Exception {
        P origin=foot();
        for(int r:new int[]{8,16,32,LIQUID_RADIUS}) {
            survey(origin,r);
            Set<P> component=liquidComponent(origin,d,r);
            if(component==null) continue;
            // Select any safe rejoin point on the original forward ray beyond the obstruction.
            int beyond=6;
            for(P p:component) {
                int along=(p.x-origin.x)*d[0]+(p.z-origin.z)*d[2];
                int across=Math.abs((p.x-origin.x)*d[2]-(p.z-origin.z)*d[0]);
                if(across<=1 && p.y>=origin.y-1 && p.y<=origin.y+2) beyond=Math.max(beyond,along+2);
            }
            final int rejoin=beyond;
            var route=path(origin,p->branchEnd!=null ? p.equals(branchEnd) : p.y==origin.y &&
                    (p.x-origin.x)*d[2]-(p.z-origin.z)*d[0]==0 &&
                    (p.x-origin.x)*d[0]+(p.z-origin.z)*d[2]>=rejoin,
                    p->standable(p) && p.y==origin.y,this::excavationCost,false,r-2);
            if(route!=null) {
                log("绕液体："+route.size()+"步，回归原主道直线");
                follow(route,true);traceOres();return true;
            }
        }
        return false;
    }
    // Holes are obstacles, not permission to bridge or descend into unknown space.
    List<P> pitRoute(P origin,int[] d,P branchEnd,int radius) {
        return path(origin,p->branchEnd!=null ? p.equals(branchEnd) : p.y==origin.y &&
                (p.x-origin.x)*d[2]-(p.z-origin.z)*d[0]==0 &&
                (p.x-origin.x)*d[0]+(p.z-origin.z)*d[2]>=2,
                p->p.y==origin.y && standable(p),this::excavationCost,false,radius-2);
    }
    boolean bypassPit(int[] d,P branchEnd) throws Exception {
        P origin=foot();
        for(int radius:new int[]{8,16,32,LIQUID_RADIUS}) {
            survey(origin,radius);
            var route=pitRoute(origin,d,branchEnd,radius);
            if(route!=null) {
                log("绕坑："+route.size()+"步，保持高度并回归原路线");
                follow(route,true);traceOres();return true;
            }
        }
        return false;
    }
    void turnMain() throws Exception {
        P p=foot();
        for(int candidate:new int[]{(heading+3)%4,(heading+1)%4}) {
            int[] turn=HORIZONTAL[candidate];scanForward(p,turn);localTerrain(p.add(turn));
            if(!forwardLiquid(p,turn) && standable(p.add(turn))) {
                heading=candidate;log("障碍无可用绕行路线，主道转向="+heading);return;
            }
        }
        throw new Stop("障碍无法绕行，左右也无安全方向");
    }
    boolean advance(boolean main,P branchEnd) throws Exception {
        P p=foot();int[] d=HORIZONTAL[heading];
        scanForward(p,d);
        if(forwardLiquid(p,d)) {
            if(bypass(d,branchEnd)) return false;
            if(!main) { branchNeedsTurn=true;return false; }
            turnMain();return false;
        }
        localTerrain(p.add(d));
        if(!support(at(p.add(d).add(0,-1,0)))) {
            log("前方落脚面缺失，尝试绕坑");
            if(bypassPit(d,branchEnd)) return false;
            if(!main) { branchNeedsTurn=true;return false; }
            turnMain();return false;
        }
        step(p.add(d),false);traceOres();return true;
    }
    void run() throws Exception {
        try {
            var initial=guard();dimension=initial.dimension();
            var p=mc.position();level=foot().y;
            heading=Math.floorMod((int)Math.round(p.yaw()/90),4);
            opened.add(foot());opened.add(foot().add(0,1,0));
            localTerrain(foot());traceOres();
            while(true) {
                int count=0;
                while(count<3) { guard();if(advance(true,null)) count++;else count=0; }
                P anchor=foot();int mainHeading=heading;
                for(int side:new int[]{3,1}) {
                    heading=(mainHeading+side)%4;
                    P end=anchor.along(HORIZONTAL[heading],5);
                    for(int i=0;i<5;i++) if(!advance(false,end)) break;
                    returnTo(anchor);
                    if(branchNeedsTurn) break;
                }
                heading=mainHeading;
                if(branchNeedsTurn) { branchNeedsTurn=false;turnMain(); }
            }
        } catch(Stop e) { log("停止："+e.getMessage()+"；待处理矿石="+oreCandidates.size()); }
        catch(Exception e) { log("异常停止："+e+"；待处理矿石="+oreCandidates.size());throw e; }
        finally { mc.mouse().up(LEFT);mc.key().up("w");mc.key().up("space"); }
    }
}
