import dev.minecontrol.sdk.MineControl;
import dev.minecontrol.sdk.CommandException;
import dev.minecontrol.sdk.Snapshots.*;
import java.util.*;
import java.util.function.Predicate;
import java.nio.file.*;
import com.google.gson.*;
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
            "tuff", "calcite", "smooth_basalt", "amethyst_block", "dirt", "grass_block", "cobblestone", "cobbled_deepslate",
            "netherrack", "sandstone", "red_sandstone", "clay", "end_stone");
    record P(int x, int y, int z) {
        P add(int x, int y, int z) { return new P(this.x+x,this.y+y,this.z+z); }
        P add(int[] d) { return add(d[0],d[1],d[2]); }
        P along(int[] d, int n) { return add(d[0]*n,d[1]*n,d[2]*n); }
        BlockPos block() { return new BlockPos(x,y,z); }
        int distance(P b) { return Math.abs(x-b.x)+Math.abs(y-b.y)+Math.abs(z-b.z); }
    }
    static class Stop extends RuntimeException { Stop(String reason) { super(reason); } }
    static final class Obstacle extends Stop {
        final P position;
        Obstacle(P position,String reason){super(reason);this.position=position;}
    }
    static final class Stationary extends RuntimeException {}
    final MineControl mc;
    final Map<P,String> terrain = new HashMap<>();
    final Map<P,Integer> turnVisits = new HashMap<>();
    final Set<P> opened = new HashSet<>();
    final Set<P> oreCandidates = new LinkedHashSet<>();
    final Set<P> minedOres = new HashSet<>();
    final Set<P> obstacles = new HashSet<>();
    final Map<P,Integer> diggingFailures = new HashMap<>();
    P stationaryPosition;
    long stationarySince;
    String action="启动";
    static final long STATIONARY_NANOS=180_000_000_000L;
    boolean stationary(P position,long now) {
        if(!position.equals(stationaryPosition)) { stationaryPosition=position;stationarySince=now;return false; }
        return now-stationarySince>=STATIONARY_NANOS;
    }
    final List<String> audit = new ArrayList<>();
    String dimension;
    int level, heading;
    boolean lowTriggered;
    boolean inStorage;
    boolean recovering;
    long storagePausedNanos, storageTrips;
    long operationTime() { return System.nanoTime()-storagePausedNanos; }
    Double lastHealth;
    static boolean injuryUnload(Double previous,double health) {
        return health<10 || (previous!=null && health<previous);
    }
    boolean branchNeedsTurn;
    static final Path ROUTE_STATE=Path.of("fishbone-route-state.json");
    static final Gson ROUTE_JSON=new Gson();
    record RouteState(String world,String dimension,int level,int heading,int mainCount,
                      int mainHeading,int sideIndex,int sideCount,P anchor,P resume,
                      boolean branchNeedsTurn,Set<P> opened) {}
    int mainCount,mainHeading,sideIndex=-1,sideCount;
    P branchAnchor,resumePoint;
    String routeWorld;
    boolean routeReady;
    RouteState routeState() {
        return new RouteState(routeWorld,dimension,level,heading,mainCount,mainHeading,
                sideIndex,sideCount,branchAnchor,resumePoint,branchNeedsTurn,new HashSet<>(opened));
    }
    void writeRoute() throws Exception {
        if(!routeReady) return;
        Path temporary=ROUTE_STATE.resolveSibling(ROUTE_STATE.getFileName()+".tmp");
        Files.writeString(temporary,ROUTE_JSON.toJson(routeState()));
        Files.move(temporary,ROUTE_STATE,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    void restoreRoute(RouteState state,String world,String currentDimension) {
        if(!Objects.equals(state.world(),world) || !Objects.equals(state.dimension(),currentDimension))
            throw new Stop("路线记录属于其他世界或维度，请移走 fishbone-route-state.json 后开始新路线");
        if(state.resume()==null || state.heading()<0 || state.heading()>3 || state.opened()==null)
            throw new Stop("路线记录无效，拒绝按当前视角新建路线");
        routeWorld=world;dimension=currentDimension;level=state.level();heading=state.heading();
        mainCount=state.mainCount();mainHeading=state.mainHeading();sideIndex=state.sideIndex();
        sideCount=state.sideCount();branchAnchor=state.anchor();resumePoint=state.resume();
        branchNeedsTurn=state.branchNeedsTurn();opened.addAll(state.opened());routeReady=true;
    }
    void checkpoint() throws Exception { resumePoint=foot();writeRoute(); }

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
        double health=o.player().health();
        Double previous=lastHealth;
        boolean damaged=injuryUnload(previous,health);
        lastHealth=health;
        if(!inStorage && damaged) {
            if(dimension==null) dimension=o.dimension();
            JsonElement source=mc.call("observe",new JsonObject()).getAsJsonObject().get("last_damage");
            log("[受伤] 时间="+java.time.Instant.now()+"，血量="+previous+" -> "+health+
                    "，坐标="+new P((int)Math.floor(o.player().x()),(int)Math.floor(o.player().y()),(int)Math.floor(o.player().z()))+
                    "，动作="+action+"，伤害来源="+(source==null?"未知（客户端未提供）":source)+"，处理=回仓卸货恢复");
            releaseStorageMovement();unload(true);return guard();
        }
        if(!inStorage && o.player().food()<6) throw new Stop("饥饿保护");
        if(!inStorage && !recovering && stationary(new P((int)Math.floor(o.player().x()),(int)Math.floor(o.player().y()),(int)Math.floor(o.player().z())),operationTime()))
            throw new Stationary();
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
    static boolean flag(String s,String name) { return s!=null && s.contains(name+"=true"); }
    static boolean falling(String s) { return flag(s,"_mc_falling") || Set.of("minecraft:gravel", "minecraft:sand", "minecraft:red_sand").contains(id(s)); }
    static boolean container(String s) {
        return flag(s,"_mc_container") || Set.of("minecraft:chest","minecraft:trapped_chest","minecraft:barrel","minecraft:ender_chest",
                "minecraft:furnace","minecraft:blast_furnace","minecraft:smoker","minecraft:hopper","minecraft:dispenser","minecraft:dropper",
                "minecraft:brewing_stand","minecraft:decorated_pot").contains(id(s)) || id(s).endsWith("shulker_box");
    }
    static boolean crystalGrowth(String s) {
        return Set.of("minecraft:amethyst_cluster","minecraft:small_amethyst_bud",
                "minecraft:medium_amethyst_bud","minecraft:large_amethyst_bud").contains(id(s));
    }
    static boolean diggable(String s) {
        if(s==null || liquid(s) || container(s)) return false;
        if(Set.of("minecraft:bedrock","minecraft:reinforced_deepslate","minecraft:trial_spawner","minecraft:vault",
                "minecraft:barrier","minecraft:end_portal","minecraft:end_portal_frame","minecraft:nether_portal","minecraft:command_block",
                "minecraft:chain_command_block","minecraft:repeating_command_block","minecraft:structure_block","minecraft:jigsaw").contains(id(s))) return false;
        var m=java.util.regex.Pattern.compile("_mc_hardness=([^\\s]+)").matcher(s);
        return !m.find() || Double.parseDouble(m.group(1))>=0;
    }
    static boolean support(String s) {
        if(s==null || air(s) || liquid(s) || container(s) || falling(s) || crystalGrowth(s)) return false;
        if(s.contains("_mc_top_full=")) return flag(s,"_mc_top_full");
        return ROCK.contains(id(s).replace("minecraft:","")) || ore(s) || id(s).equals("minecraft:bedrock") || id(s).equals("minecraft:budding_amethyst");
    }
    static boolean repairableFloor(String s) { return !air(s) && diggable(s); }
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
        var request=new JsonObject();request.add("from",ROUTE_JSON.toJsonTree(new int[]{a.x,a.y,a.z}));
        request.add("to",ROUTE_JSON.toJsonTree(new int[]{b.x,b.y,b.z}));request.addProperty("mining_metadata",true);
        var blocks=parse(mc.call("region.query",request).getAsJsonObject().get("blueprint").getAsString());
        for (int x=Math.min(a.x,b.x);x<=Math.max(a.x,b.x);x++)
            for (int y=Math.min(a.y,b.y);y<=Math.max(a.y,b.y);y++)
                for (int z=Math.min(a.z,b.z);z<=Math.max(a.z,b.z);z++) {
                    P p=new P(x,y,z);String s=blocks.getOrDefault(p,"minecraft:air");
                    terrain.put(p,!local && ore(s)?s.replaceFirst("^minecraft:[^\\s]+","minecraft:stone"):s);
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
        return !obstacles.contains(p) && !obstacles.contains(p.add(0,1,0)) && !obstacles.contains(p.add(0,-1,0)) &&
                (support(at(p.add(0,-1,0))) || repairableFloor(at(p.add(0,-1,0)))) && diggable(at(p)) && diggable(at(p.add(0,1,0)))
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
        action="清理方块 "+q;
        scan(q,q,false);
        if(air(at(q))) { opened.add(q);return false; }
        if(obstacles.contains(q) || !diggable(at(q))) throw new Obstacle(q,"方块不可挖或已重试5次："+q+" "+at(q));
        while(diggingFailures.getOrDefault(q,0)<5) {
            int attempt=diggingFailures.getOrDefault(q,0)+1;
            log("[正常补救] 开挖尝试 "+attempt+"/5，目标="+q+"，方块="+at(q));
            try {
                boolean changed=clearAttempt(q);diggingFailures.remove(q);return changed;
            } catch(Stop failure) {
                if(failure instanceof Obstacle) throw failure;
                String reason=failure.getMessage();
                if(!(reason.startsWith("挖掘超时") || reason.startsWith("方块不可见") || reason.startsWith("开挖期间目标变化") || reason.startsWith("落沙持续") || reason.startsWith("视角校准失败"))) throw failure;
                int count=diggingFailures.merge(q,1,Integer::sum);
                log("[正常补救] 开挖未成功，目标="+q+"，次数="+count+"/5，原因="+reason);
                retryPause(q);
                if(air(at(q))) { opened.add(q);diggingFailures.remove(q);return true; }
            } catch(CommandException failure) {
                if(!Set.of("TIMEOUT","TARGET_CHANGED","OUT_OF_REACH").contains(failure.code())) throw failure;
                int count=diggingFailures.merge(q,1,Integer::sum);
                log("[正常补救] 开挖接口暂时失败，目标="+q+"，次数="+count+"/5，原因="+failure.getMessage());
                retryPause(q);
            }
        }
        obstacles.add(q);
        throw new Obstacle(q,"开挖5次未成功，转入绕行："+q);
    }
    void retryPause(P target) throws Exception { mc.mouse().up(LEFT);releaseWalking();mc.waitTicks(4);localTerrain(foot()); }
    boolean clearAttempt(P q) throws Exception {
        scan(q,q.add(0,1,0),false);
        boolean settling=falling(at(q)) || falling(at(q.add(0,1,0)));
        boolean changed=false;
        long deadline=operationTime()+60_000_000_000L;
        long emptySince=-1;
        while(true) {
            guard();scan(q,q.add(0,1,0),false);
            settling |= falling(at(q)) || falling(at(q.add(0,1,0)));
            if(operationTime()>deadline) throw new Stop("落沙持续掉落超过60秒："+q);
            if(!air(at(q))) {
                emptySince=-1;
                changed |= clearOnce(q);
                if(!settling) return changed;
            } else {
                if(!settling) { opened.add(q);return changed; }
                long tick=mc.status().tick();
                if(emptySince<0) emptySince=tick;
                // Stay outside the column while falling entities turn back into blocks.
                if(tick-emptySince>=20) {
                    opened.add(q);log("落沙清理完成，连续20 tick无新掉落："+q);return changed;
                }
                mc.waitTicks(2);
            }
        }
    }
    boolean clearOnce(P q) throws Exception {
        guard();
        scan(q,q,false);
        if(air(at(q))) { opened.add(q);return false; }
        unloadIfNeeded();
        if(q.equals(foot().add(0,-1,0))) throw new Stop("拒绝挖除自身脚下支撑；矿脉未完成");
        scan(q.add(-1,-1,-1),q.add(1,1,1),false);
        if(!safeCell(q)) throw new Obstacle(q,"开挖位置邻近液体或未知边界："+q);
        Target t=sight(q);
        if(t==null || !diggable(t.id())) throw new Stop("方块不可见或不支持开挖："+q);
        boolean isOre=ore(t.id());String expected=t.id();
        long end=operationTime()+12_000_000_000L;
        try {
            // Keep progress continuous, with a finite expiry and a wall-clock timeout.
            mc.mouse().hold(LEFT,300);
            while(true) {
                long trip=storageTrips;
                guard();
                if(trip!=storageTrips) {
                    scan(q,q,false);
                    if(air(at(q))) break;
                    sight(q);mc.mouse().hold(LEFT,300);
                }
                scan(q,q,false);
                if(air(at(q))) break;
                var current=mc.observe().target();
                if(current==null || !q.block().equals(current.pos()) || !expected.equals(current.id())) {
                    // The block can break between the region sample and crosshair sample.
                    mc.mouse().up(LEFT);
                    scan(q,q,false);
                    if(air(at(q))) break;
                    throw new Stop("开挖期间目标变化："+q);
                }
                if(operationTime()>end) throw new Stop("挖掘超时："+q);
            }
        } finally { mc.mouse().up(LEFT); }
        opened.add(q);
        if(isOre) { minedOres.add(q);oreCandidates.remove(q);discover(q); }
        log("挖除 "+q+" "+expected);
        return true;
    }
    static final Path STORAGE_PENDING=Path.of("fishbone-unload-state.json");
    static final Gson STORAGE_JSON=new GsonBuilder().setPrettyPrinting().create();
    record StorageReturn(String world,Position position) {}
    static int emptyInventory(Inventory inventory) {
        return 27-(int)inventory.slots().stream().filter(s->s.slot()>=9 && s.slot()<36 &&
                s.stack()!=null && s.stack().count()>0).count();
    }
    static int cargoCount(Inventory inventory) {
        return inventory.slots().stream().filter(s->s.slot()>=9 && s.slot()<36 && s.stack()!=null)
                .mapToInt(s->s.stack().count()).sum();
    }
    String storageWorld() throws Exception {
        var status=mc.call("status",new JsonObject()).getAsJsonObject();
        if(!status.has("world")) throw new Stop("当前主Mod不支持仓库世界校验，请使用更新后的26.2版本");
        return status.get("world").getAsString();
    }
    void storageHealth() throws Exception {
        var o=mc.observe();
        if(Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if(o.player()==null || !o.player().alive()) throw new Stop("卸货期间玩家已死亡或世界不可用");
    }
    static final List<String> SAVE_MINE_HOME=List.of("delhome kuang","sethome kuang");
    static final String WAREHOUSE_HOME="home home",MINE_HOME="home kuang";
    void releaseStorageMovement() throws Exception {
        mc.mouse().up(LEFT);mc.key().up("w");mc.key().up("space");mc.key().up("left_shift");
    }
    void saveMineHome() throws Exception {
        releaseStorageMovement();
        for(String command:SAVE_MINE_HOME) {
            storageHealth();mc.sendCommand(command);log("已发送：/"+command);
            // Preserve command order and allow the server to process the previous request.
            mc.waitTicks(20);
        }
    }
    static boolean atMiningPoint(Position current,Position destination) {
        return Objects.equals(current.dimension(),destination.dimension()) &&
                // Server home landings can settle slightly over one block from the saved position.
                Math.hypot(current.x()-destination.x(),current.z()-destination.z())<=1.25 &&
                Math.abs(current.y()-destination.y())<.2;
    }
    static boolean chestTarget(Target target) {
        return target!=null && "block".equals(target.kind()) && target.pos()!=null &&
                Set.of("minecraft:chest","minecraft:trapped_chest").contains(target.id()) && target.distance()<=4.3;
    }
    BlockPos warehouseHome(String world) throws Exception {
        releaseStorageMovement();
        if(!world.equals(storageWorld())) throw new Stop("回仓前世界改变");
        mc.sendCommand(WAREHOUSE_HOME);log("已发送：/"+WAREHOUSE_HOME+"；等待落地并识别面前箱子");
        // The home plugin restores the view. Do not scan for or choose a nearby alternative.
        mc.waitTicks(100);
        long deadline=System.nanoTime()+60_000_000_000L;
        while(System.nanoTime()<deadline) {
            storageHealth();
            if(!world.equals(storageWorld())) throw new Stop("回仓期间世界改变");
            mc.position();var observed=mc.observe();var target=observed.target();
            if(observed.active() && Boolean.TRUE.equals(mc.status().onGround()) && chestTarget(target)) {
                var selected=target.pos();mc.waitTicks(3);
                var again=mc.observe().target();
                if(chestTarget(again) && selected.equals(again.pos())) {
                    log("已识别并锁定面前箱子（仅本次卸货内存）："+selected);return selected;
                }
            }
            Thread.sleep(500);
        }
        throw new Stop("回仓后未确认面前可交互箱子；不搜索或更换其他箱子，保留返矿记录");
    }
    void storageHome(String command,String world,Predicate<Position> arrived) throws Exception {
        if(!world.equals(storageWorld())) throw new Stop("仓库配置与当前世界不符，拒绝传送");
        releaseStorageMovement();
        if(arrived.test(mc.position()) && mc.observe().active()) {
            log("已位于目标位置，无需重复发送 /"+command);return;
        }
        mc.sendCommand(command);log("已发送：/"+command+"；等待传送完成");
        long deadline=System.nanoTime()+60_000_000_000L;
        while(System.nanoTime()<deadline) {
            storageHealth();var current=mc.position();
            if(!world.equals(storageWorld())) throw new Stop("等待home传送时世界改变");
            if(arrived.test(current) && mc.observe().active() && Boolean.TRUE.equals(mc.status().onGround())) {
                mc.waitTicks(3);
                if(arrived.test(mc.position())) return;
            }
            Thread.sleep(100);
        }
        log("[补救未完成] home确认超时，命令=/"+command+"，实际位置="+mc.position()+
                "，active="+mc.observe().active()+"，onGround="+mc.status().onGround());
        throw new Stop("/"+command+" 传送60秒内未确认（权限、冷却、站立等待或home位置）；保留返回位置文件");
    }
    void returnMiningHome(Position origin,String world) throws Exception {
        log("[正常补救] 返矿确认目标="+origin);
        storageHome(MINE_HOME,world,p->atMiningPoint(p,origin));
    }
    static JsonObject storageParams(Object... pairs) {
        var result=new JsonObject();
        for(int i=0;i<pairs.length;i+=2) result.addProperty((String)pairs[i],(Number)pairs[i+1]);
        return result;
    }
    static boolean chestFull(JsonObject snapshot) {
        int slots=0;
        for(var value:snapshot.getAsJsonArray("slots")) {
            var slot=value.getAsJsonObject();
            if(slot.get("inventory_slot").getAsInt()!=-1) continue;
            if(!slot.has("max_count")) throw new Stop("请更新并重启主Mod，箱子快照缺少最大堆叠容量");
            slots++;
            if(slot.get("count").getAsInt()<slot.get("max_count").getAsInt() || slot.get("count").getAsInt()==0) return false;
        }
        return slots==54;
    }
    void closeStorage() throws Exception {
        if(mc.status().screen()==null) return;
        try {
            var chest=mc.call("container",new JsonObject()).getAsJsonObject();
            mc.call("container.close",storageParams("container_id",chest.get("container_id").getAsInt()));
            mc.waitScreen("none",5000);
        } catch(CommandException error) {
            if(!"NO_LARGE_CHEST".equals(error.code())) throw error;
        }
    }
    void recoverStorageReturn() throws Exception {
        if(!Files.exists(STORAGE_PENDING)) return;
        var saved=STORAGE_JSON.fromJson(Files.readString(STORAGE_PENDING),StorageReturn.class);
        closeStorage();
        var observation=mc.observe();
        if(observation.player()==null || !observation.player().alive()) throw new Stop("恢复卸货时玩家不可用");
        log("恢复未完成卸货：重新回仓，卸空后恢复并返矿");
        var chest=warehouseHome(saved.world());
        unloadWarehouse(saved.world(),chest);
        waitRecovery(saved.world());returnMiningHome(saved.position(),saved.world());Files.delete(STORAGE_PENDING);
        log("恢复卸货中断：已返回原挖矿位置");
    }
    void waitRecovery(String world) throws Exception {
        releaseStorageMovement();
        log("卸货后等待生命值20、饱食度20再返回矿点");
        while(true) {
            storageHealth();
            if(!world.equals(storageWorld())) throw new Stop("等待恢复期间世界改变");
            var observed=mc.observe();
            if(observed.player().health()>=20 && observed.player().food()>=20) {
                log("生命值和饱食度已回满");return;
            }
            Thread.sleep(500);
        }
    }
    void unloadIfNeeded() throws Exception { unload(false); }
    void unload(boolean forced) throws Exception {
        if(inStorage) return;
        inStorage=true;
        long started=System.nanoTime();
        try { unloadInternal(forced); } finally { storagePausedNanos+=System.nanoTime()-started;inStorage=false; }
    }
    void unloadInternal(boolean forced) throws Exception {
        if(!forced && emptyInventory(mc.inventory())>2) return;
        storageTrips++;
        String world=storageWorld();
        guard();
        if(!Boolean.TRUE.equals(mc.status().onGround())) throw new Stop("卸货前未安全落地，保留当前位置并停止");
        releaseStorageMovement();mc.waitTicks(4);
        var origin=mc.position();
        if(Files.exists(STORAGE_PENDING)) throw new Stop("存在未处理的卸货返回位置");
        var temporary=STORAGE_PENDING.resolveSibling(STORAGE_PENDING.getFileName()+".tmp");
        Files.writeString(temporary,STORAGE_JSON.toJson(new StorageReturn(world,origin)));
        Files.move(temporary,STORAGE_PENDING,StandardCopyOption.ATOMIC_MOVE);
        int before=cargoCount(mc.inventory());log((forced?"受伤触发":"背包空位不足3格")+"，记录 /sethome kuang 后传送仓库卸货；待存物品="+before);
        boolean recovered=false;
        try {
            saveMineHome();
            var chest=warehouseHome(world);
            unloadWarehouse(world,chest);
            log("卸货完成，已存入"+before+"个物品；保留快捷栏、装备和副手");
            waitRecovery(world);recovered=true;
            returnMiningHome(origin,world);
            Files.delete(STORAGE_PENDING);log("已返回挖矿位置："+origin.x()+","+origin.y()+","+origin.z());
        } catch(Exception failure) { log("卸货中断："+failure.getMessage());throw failure; }
        finally {
            closeStorage();
            if(Files.exists(STORAGE_PENDING)) log("卸货或返矿尚未完成，保留返回矿点记录；恢复完成="+recovered);
        }
    }
    void unloadWarehouse(String world,BlockPos selected) throws Exception {
        int x=selected.x(),y=selected.y(),z=selected.z();
        Integer openedContainer=null;
        while(true) {
            storageHealth();
            if(!world.equals(storageWorld())) throw new Stop("卸货期间世界改变");
            try {
                if(mc.status().screen()==null) {
                    aim(x+.5,y+.5,z+.5);var target=mc.observe().target();
                    if(!chestTarget(target) || !selected.equals(target.pos())) {
                        log("原箱子暂不可交互，等待重试；不更换箱子");Thread.sleep(500);continue;
                    }
                    mc.mouse().click(MineControl.MouseButton.RIGHT);
                    mc.waitScreen("ContainerScreen",5000);mc.waitTicks(5);
                    openedContainer=mc.call("container",new JsonObject()).getAsJsonObject().get("container_id").getAsInt();
                }
                var snapshot=mc.call("container",new JsonObject()).getAsJsonObject();
                if(openedContainer==null || openedContainer!=snapshot.get("container_id").getAsInt()) {
                    log("箱子界面已变化，关闭后仅重新打开原箱子");closeStorage();openedContainer=null;continue;
                }
                if(snapshot.get("size").getAsInt()!=54) throw new Stop("面前箱子必须为54格大箱子");
                if(!snapshot.get("cursor_empty").getAsBoolean()) {
                    log("鼠标持物，等待清空后继续卸货");Thread.sleep(500);continue;
                }
                if(cargoCount(mc.inventory())==0) break;
                if(chestFull(snapshot)) {
                    log("箱子已满，等待运输腾出空间后继续重读重试；不更换箱子");Thread.sleep(500);continue;
                }
                boolean moved=false;
                for(var value:snapshot.getAsJsonArray("slots")) {
                    var slot=value.getAsJsonObject();int inventorySlot=slot.get("inventory_slot").getAsInt();
                    if(inventorySlot<9 || inventorySlot>=36 || slot.get("count").getAsInt()<=0) continue;
                    int count=cargoCount(mc.inventory());
                    var fresh=mc.call("container",new JsonObject()).getAsJsonObject();
                    mc.call("container.deposit",storageParams("container_id",fresh.get("container_id").getAsInt(),
                            "state_id",fresh.get("state_id").getAsInt(),"slot",slot.get("slot").getAsInt()));
                    mc.waitTicks(4);
                    if(count>cargoCount(mc.inventory())) { moved=true;break; }
                }
                if(!moved) { log("背包尚未卸空，等待后重读原箱子继续卸货");Thread.sleep(500); }
            } catch(CommandException error) {
                if(Set.of("STOPPING","SYSTEM_DISABLED","WORLD_LOADING","NO_WORLD","FORBIDDEN").contains(error.code())) throw error;
                log("卸货操作重试："+error.getMessage());Thread.sleep(500);
            }
        }
        closeStorage();log("仓库大箱子处理完成，待存="+cargoCount(mc.inventory()));
    }
    static boolean bridgeMaterial(String block) {
        return "minecraft:cobblestone".equals(id(block)) || "minecraft:cobbled_deepslate".equals(id(block));
    }
    boolean returnable(P p) {
        boolean floor=support(at(p.add(0,-1,0))) || repairableFloor(at(p.add(0,-1,0))) ||
                (air(at(p.add(0,-1,0))) && opened.contains(p.add(0,-1,0)) && safeCell(p.add(0,-1,0)));
        // Keep the return trip on passages opened by this run. Otherwise a
        // shortest-path recomputation can cut straight through the cave we avoided.
        return floor && opened.contains(p) && opened.contains(p.add(0,1,0)) &&
                !obstacles.contains(p) && !obstacles.contains(p.add(0,1,0)) &&
                diggable(at(p)) && diggable(at(p.add(0,1,0)))
                && safeCell(p) && safeCell(p.add(0,1,0));
    }
    void repairFloor(P destination) throws Exception {
        P origin=foot(),floor=destination.add(0,-1,0),anchor=origin.add(0,-1,0);
        if(!air(at(floor)) || !safeCell(floor) || origin.distance(destination)!=1 || origin.y!=destination.y || !support(at(anchor)))
            throw new Stop("缺口不满足相邻、同高度、无液体的铺路条件");
        var inventory=mc.inventory();String tool=guard().mainHand().id();
        var material=inventory.slots().stream().filter(s->s.slot()>=0 && s.slot()<9 && s.stack()!=null &&
                s.stack().count()>0 && bridgeMaterial(s.stack().id())).findFirst();
        var pick=inventory.slots().stream().filter(s->s.slot()>=0 && s.slot()<9 && s.stack()!=null && tool.equals(s.stack().id())).findFirst();
        if(material.isEmpty() || pick.isEmpty()) throw new Stop("铺路需要快捷栏中的圆石或深板岩圆石与镐");
        int dx=destination.x-origin.x,dz=destination.z-origin.z;
        try {
            mc.key().hold("left_shift",600);
            // Keep motion continuous: one-tick taps plus observation calls spent most
            // of the old five-second budget braking between taps, especially off-centre.
            var initial=mc.position();
            aim(destination.x+.5,initial.y()+1.62,destination.z+.5);
            long end=System.nanoTime()+8_000_000_000L;
            try {
                mc.key().hold("w",160);
                while(true) {
                    guard();var p=mc.position();
                    double advance=(p.x()-origin.x-.5)*dx+(p.z()-origin.z-.5)*dz;
                    if(Math.abs(p.y()-origin.y)>.2)
                        throw new Stop("铺路靠近边缘时高度异常：起点Y="+origin.y+"，当前Y="+p.y());
                    if(advance>=.65) break;
                    if(System.nanoTime()>end)
                        throw new Stop("铺路靠近边缘超时：推进="+String.format(Locale.ROOT,"%.3f",advance)+"，目标=0.650；"+origin+" -> "+destination);
                    mc.waitTicks(1);
                }
            } finally { mc.key().up("w"); }
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
            opened.remove(floor);log("铺路填补地板 "+floor);
        } finally {
            mc.mouse().up(MineControl.MouseButton.RIGHT);mc.key().up("w");
            mc.key().tap(Integer.toString(pick.get().slot()+1));mc.key().up("left_shift");
        }
    }
    // A mined floor at the higher return cell needs a riser, not a level bridge.
    void repairUpFloor(P destination) throws Exception {
        P origin=foot(),floor=destination.add(0,-1,0),base=floor.add(0,-1,0);
        if(destination.y!=origin.y+1 || Math.abs(destination.x-origin.x)+Math.abs(destination.z-origin.z)!=1
                || !air(at(floor)) || !safeCell(floor))
            throw new Stop("上台阶缺口没有安全放置支撑："+origin+" -> "+destination);
        var inventory=mc.inventory();String tool=guard().mainHand().id();
        var material=inventory.slots().stream().filter(s->s.slot()>=0 && s.slot()<9 && s.stack()!=null
                && s.stack().count()>0 && bridgeMaterial(s.stack().id())).findFirst();
        var pick=inventory.slots().stream().filter(s->s.slot()>=0 && s.slot()<9 && s.stack()!=null
                && tool.equals(s.stack().id())).findFirst();
        if(material.isEmpty() || pick.isEmpty()) throw new Stop("铺路需要快捷栏中的圆石或深板岩圆石与镐");
        boolean visible=false;
        var anchors=new ArrayList<P>();anchors.add(base);
        for(int[] d:HORIZONTAL) anchors.add(floor.add(d));
        for(P anchor:anchors) {
            if(!support(at(anchor))) continue;
            int dx=floor.x-anchor.x,dy=floor.y-anchor.y,dz=floor.z-anchor.z;
            aim(anchor.x+.5+dx*.499,anchor.y+.5+dy*.499,anchor.z+.5+dz*.499);
            var target=guard().target();
            String face=dy>0?"up":dx>0?"east":dx<0?"west":dz>0?"south":"north";
            if(target!=null && anchor.block().equals(target.pos()) && face.equals(target.face())) { visible=true;break; }
        }
        if(!visible)
            throw new Stop("无法看见返回台阶的放置面");
        try {
            mc.key().tap(Integer.toString(material.get().slot()+1));
            mc.mouse().click(MineControl.MouseButton.RIGHT);mc.waitTicks(3);
        } finally {
            mc.mouse().up(MineControl.MouseButton.RIGHT);
            mc.key().tap(Integer.toString(pick.get().slot()+1));
        }
        scan(floor,floor,false);
        if(!support(at(floor))) throw new Stop("返回台阶放置未确认");
        opened.remove(floor);log("修复返回台阶 "+floor);
    }
    void releaseWalking() throws Exception { mc.key().up("w");mc.key().up("space"); }
    void step(P q,boolean fresh) throws Exception {
        try { stepAttempt(q,fresh); }
        catch(Stop failure) {
            if(failure instanceof Obstacle) throw failure;
            String reason=failure.getMessage();
            if(reason.startsWith("铺路") || reason.startsWith("无法看见") || reason.startsWith("上台阶") || reason.startsWith("返回台阶") ||
                    reason.startsWith("地板") || reason.startsWith("缺口") || reason.startsWith("开挖后通道") || reason.startsWith("移动超时") || reason.startsWith("跨坑"))
                throw new Obstacle(q,reason);
            throw failure;
        }
    }
    void stepAttempt(P q,boolean fresh) throws Exception {
        action="移动至 "+q;
        guard();P from=foot();
        if(Math.abs(from.x-q.x)+Math.abs(from.z-q.z)!=1 || Math.abs(from.y-q.y)>1)
            throw new Stop("非相邻移动："+from+" -> "+q);
        if(fresh) scanForward(from,new int[]{q.x-from.x,0,q.z-from.z});
        localTerrain(q);
        P floor=q.add(0,-1,0);
        if(!support(at(floor)) && !air(at(floor))) {
            if(!support(at(from.add(0,-1,0)))) throw new Obstacle(floor,"当前位置没有可靠支撑，拒绝清除前方地板");
            if(!diggable(at(floor))) throw new Obstacle(floor,"前方地板不可修复："+floor+" "+at(floor));
            log("[正常补救] 清除不完整或重力地板并回填："+floor+" "+at(floor));
            // Expose the floor from the reliable adjacent standing position first.
            // A solid feet/head cell otherwise occludes every face of the floor.
            clear(q.add(0,1,0));clear(q);localTerrain(q);
            if(!support(at(floor)) && !air(at(floor))) clear(floor);
            localTerrain(q);
        }
        if(!support(at(q.add(0,-1,0)))) {
            if(q.y==from.y+1) {
                repairUpFloor(q);
                localTerrain(q);
            } else if(q.y!=from.y) throw new Stop("跨坑铺路必须保持高度："+from+" -> "+q);
            // A floating wall above a pit otherwise stops the player before its
            // supporting block's side face becomes visible for placement.
            if(!support(at(q.add(0,-1,0)))) {
                clear(q.add(0,1,0));clear(q);
                localTerrain(q);
                // Clearing falling gravel may fill the gap with a solid floor.
                if(!support(at(q.add(0,-1,0)))) repairFloor(q);
            }
        }
        if(!standable(q)) {
            if(!support(at(q.add(0,-1,0)))) throw new Obstacle(q,"目标位置无安全支撑："+q);
            for(P cell:List.of(q,q.add(0,1,0)))
                if(!diggable(at(cell))) throw new Obstacle(cell,"通道方块不支持开挖："+cell+" "+at(cell));
            throw new Obstacle(q,"目标通道周围不安全："+q);
        }
        if(q.y>from.y) clear(from.add(0,2,0));
        for(int y=Math.max(from.y,q.y)+1;y>=q.y;y--) clear(new P(q.x,y,q.z));
        localTerrain(q);
        if(!standable(q) || !air(at(q)) || !air(at(q.add(0,1,0)))) throw new Stop("开挖后通道不安全");
        long end=operationTime()+7_000_000_000L;
        try {
            while(true) {
                guard();var p=mc.position();
                double distance=Math.hypot(p.x()-q.x-.5,p.z()-q.z-.5);
                if(distance<.18 && Math.abs(p.y()-q.y)<.15 && Boolean.TRUE.equals(mc.status().onGround())) break;
                if(operationTime()>end || Math.abs(p.y()-from.y)>1.5) throw new Stop("移动超时或高度异常");
                aim(q.x+.5,p.y()+1.62,q.z+.5);
                if(q.y>p.y()+.2) { mc.key().up("space");mc.key().hold("space",5); }
                mc.key().holdAndWait("w",distance<.45?1:2);
                mc.key().up("w");
            }
        } finally { releaseWalking(); }
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
    List<P> knownReturnRoute(P start,P anchor) {
        return path(start,anchor::equals,p->opened.contains(p) && opened.contains(p.add(0,1,0)),p->1,true,64);
    }
    void returnTo(P anchor) throws Exception {
        // Restarts restore visited cells, but not the old terrain cache. Refresh the
        // known corridor before judging it unsafe; scanning never authorises new ores.
        P start=foot();localTerrain(start);
        var corridor=knownReturnRoute(start,anchor);
        if(corridor!=null) for(P cell:corridor) localTerrain(cell);
        var back=path(foot(),anchor::equals,this::returnable,p->support(at(p.add(0,-1,0)))?1:4,true,64);
        if(back==null) throw new Obstacle(anchor,"返回锚点无安全路线（地板、液体或已知范围限制）："+anchor);
        follow(back,false);
    }
    void traceOres() throws Exception {
        P anchor=foot();
        discover(anchor);discover(anchor.add(0,1,0));
        while(!oreCandidates.isEmpty()) {
            guard();
            P here=foot();
            P target=oreCandidates.stream().min(Comparator.comparingInt(here::distance)).orElseThrow();
            if(obstacles.contains(target)) { oreCandidates.remove(target);log("[补救未完成] 暂跳过矿石："+target+"，原因=本次运行已标记障碍");continue; }
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
                        p->standable(p) && !p.equals(target) && !p.add(0,1,0).equals(target),this::excavationCost,true,64);
                if(route==null) {
                    log("[补救未完成] 已发现矿石无安全接近路线：当前位置="+start+"，目标="+target+"，方块="+at(target)+"，范围=64，条件=可靠地板/可清理通道/无液体，处理=记录并跳过");
                    recordUnfinished(target,"64格范围内无安全接近路线");obstacles.add(target);oreCandidates.remove(target);continue;
                }
                follow(route,false);
            }
            if(sight(target)==null) { recordUnfinished(target,"到达后仍不可见或距离不合适");obstacles.add(target);oreCandidates.remove(target);continue; }
            try { clear(target); }
            catch(Obstacle blocked) { recordUnfinished(target,blocked.getMessage());obstacles.add(target);oreCandidates.remove(target);continue; }
            oreCandidates.remove(target);
        }
        returnTo(anchor);
        log("当前六面相连矿脉可处理目标已完成，跳过目标见 fishbone-unfinished-ores.jsonl，返回 "+anchor);
    }
    void recordUnfinished(P target,String reason) throws Exception {
        var entry=new JsonObject();entry.addProperty("time",java.time.Instant.now().toString());
        entry.addProperty("world",routeWorld);entry.addProperty("dimension",dimension);entry.add("position",ROUTE_JSON.toJsonTree(target));
        entry.addProperty("block",at(target));entry.addProperty("reason",reason);
        Files.writeString(Path.of("fishbone-unfinished-ores.jsonl"),entry+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        log("[补救未完成] 矿石记录="+entry);
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
        log("障碍水平勘探半径="+radius);
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
    boolean naturalCave(P p) {
        return air(at(p)) && air(at(p.add(0,1,0))) &&
                !opened.contains(p) && !opened.contains(p.add(0,1,0));
    }
    List<P> caveRoute(P origin,int[] d,P branchEnd,int radius) {
        return path(origin,p->branchEnd!=null ? p.equals(branchEnd) :
                (p.x-origin.x)*d[2]-(p.z-origin.z)*d[0]==0 &&
                (p.x-origin.x)*d[0]+(p.z-origin.z)*d[2]>=2,
                p->p.y==origin.y && standable(p) && !naturalCave(p),this::excavationCost,false,radius-2);
    }
    boolean bypassCave(int[] d,P branchEnd) throws Exception {
        P origin=foot();
        for(int radius:new int[]{8,16,32,LIQUID_RADIUS}) {
            survey(origin,radius);
            var route=caveRoute(origin,d,branchEnd,radius);
            if(route!=null) {
                log("绕天然矿洞："+route.size()+"步，保持高度并回归原路线");
                follow(route,true);traceOres();return true;
            }
        }
        return false;
    }
    int unopenedHeading(P p) {
        for(int candidate=0;candidate<4;candidate++) {
            int[] d=HORIZONTAL[candidate];P next=p.add(d);
            if(!opened.contains(next) && standable(next) && !naturalCave(next) && !forwardLiquid(p,d)) return candidate;
        }
        return -1;
    }
    boolean escapeTurnLoop() throws Exception {
        var route=path(foot(),p->unopenedHeading(p)>=0,this::returnable,p->1,false,64);
        if(route==null) return false;
        P exit=route.isEmpty()?foot():route.get(route.size()-1);
        int direction=unopenedHeading(exit);
        follow(route,false);localTerrain(foot());scanForward(foot(),HORIZONTAL[direction]);
        if(unopenedHeading(foot())!=direction) return false;
        heading=direction;turnVisits.clear();
        log("检测到重复转向，沿已知安全通道退出循环："+exit+"，新方向="+heading);return true;
    }
    void turnMain() throws Exception {
        if(turnVisits.merge(foot(),1,Integer::sum)>1) {
            if(escapeTurnLoop()) return;
            throw new Stop("重复转向且没有可达的新开挖出口，停止绕圈");
        }
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
        if(!diggable(at(p.add(d))) || !diggable(at(p.add(d).add(0,1,0)))) {
            log("前方为保留或不支持开挖的方块，尝试安全绕行");
            if(bypassCave(d,branchEnd)) return false;
            if(!main) { branchNeedsTurn=true;return false; }
            turnMain();return false;
        }
        if(!support(at(p.add(d).add(0,-1,0)))) {
            log("前方坑洞，使用快捷栏圆石或深板岩圆石逐格铺路");
            step(p.add(d),false);traceOres();return true;
        }
        if(naturalCave(p.add(d))) {
            log("前方天然矿洞，尝试同高度绕行");
            if(bypassCave(d,branchEnd)) return false;
            if(!main) { branchNeedsTurn=true;return false; }
            turnMain();return false;
        }
        step(p.add(d),false);traceOres();return true;
    }
    void resetMiningRoute(String reason) throws Exception {
        releaseStorageMovement();
        Path backup=ROUTE_STATE.resolveSibling("fishbone-route-state.recovery-"+java.util.UUID.randomUUID()+".json.bak");
        if(Files.exists(ROUTE_STATE)) Files.copy(ROUTE_STATE,backup);
        P origin=foot();localTerrain(origin);
        log("[正常补救] 重置路线，原因="+reason+"，原路线备份="+backup+"，新起点="+origin);
        for(int n=0;n<4;n++) {
            int candidate=(heading+n)%4;int[] d=HORIZONTAL[candidate];
            scanForward(origin,d);localTerrain(origin.add(d));
            if(standable(origin.add(d)) && !forwardLiquid(origin,d)) { heading=candidate;break; }
        }
        sideIndex=-1;sideCount=0;mainCount=0;mainHeading=heading;branchAnchor=null;branchNeedsTurn=false;
        opened.add(origin);opened.add(origin.add(0,1,0));stationaryPosition=origin;stationarySince=operationTime();checkpoint();
    }
    void recoverObstacle(P target,String reason) throws Exception {
        recovering=true;
        try {
        releaseStorageMovement();stationaryPosition=foot();stationarySince=operationTime();
        log("[正常补救] 尝试绕过障碍，坐标="+target+"，原因="+reason);
        if(target!=null) obstacles.add(target);
        try {
            localTerrain(foot());
            boolean bypassed=bypassCave(HORIZONTAL[heading],null);
            if(bypassed) {
                sideIndex=-1;mainCount=0;sideCount=0;branchAnchor=null;branchNeedsTurn=false;mainHeading=heading;
                checkpoint();log("[正常补救] 绕行成功，继续挖矿");return;
            }
        } catch(Obstacle failure) { log("[补救未完成] 绕行受阻："+failure.getMessage()); }
        resetMiningRoute(reason+"；无安全绕行路线");
        } finally { recovering=false;stationaryPosition=foot();stationarySince=operationTime(); }
    }
    void run() throws Exception {
        try {
            recoverStorageReturn();
            var initial=guard();dimension=initial.dimension();
            String world=mc.call("status",new JsonObject()).getAsJsonObject().get("world").getAsString();
            if(Files.exists(ROUTE_STATE)) {
                restoreRoute(ROUTE_JSON.fromJson(Files.readString(ROUTE_STATE),RouteState.class),world,dimension);
                log("恢复原路线：方向="+heading+"，基准Y="+level+"，主道进度="+mainCount+"，支路="+sideIndex+"/"+sideCount);
                localTerrain(foot());
                try { returnTo(resumePoint); }
                catch(Obstacle failure) { recoverObstacle(failure.position,failure.getMessage()); }
            } else {
                var p=mc.position();level=foot().y;
                heading=Math.floorMod((int)Math.round(p.yaw()/90),4);
                routeWorld=world;routeReady=true;
                opened.add(foot());opened.add(foot().add(0,1,0));
                checkpoint();localTerrain(foot());traceOres();checkpoint();
            }
            while(true) {
                try {
                guard();checkpoint();
                if(sideIndex<0) {
                    if(mainCount<3) { if(advance(true,null)) mainCount++;else mainCount=0; }
                    else { branchAnchor=foot();mainHeading=heading;sideIndex=0;sideCount=0; }
                } else if(sideIndex<2) {
                    heading=(mainHeading+(sideIndex==0?3:1))%4;
                    if(sideCount<5) {
                        P end=branchAnchor.along(HORIZONTAL[heading],5);
                        if(advance(false,end)) sideCount++;else sideCount=5;
                    } else {
                        returnTo(branchAnchor);sideIndex++;sideCount=0;
                        if(branchNeedsTurn) sideIndex=2;
                    }
                } else {
                    heading=mainHeading;
                    if(branchNeedsTurn) { branchNeedsTurn=false;turnMain(); }
                    sideIndex=-1;mainCount=0;branchAnchor=null;
                }
                checkpoint();
                } catch(Obstacle failure) { recoverObstacle(failure.position,failure.getMessage()); }
                catch(Stationary stalled) { recoverObstacle(null,"整数坐标连续3分钟未变化："+stationaryPosition); }
            }
        } catch(Stop e) { mc.exitReason(e.getMessage());log("停止："+e.getMessage()+"；待处理矿石="+oreCandidates.size()); }
        catch(Exception e) { log("异常停止："+e+"；待处理矿石="+oreCandidates.size());throw e; }
        finally {
            try { writeRoute(); }
            finally { mc.mouse().up(LEFT);mc.key().up("w");mc.key().up("space"); }
        }
    }
}
