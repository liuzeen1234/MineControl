import dev.minecontrol.sdk.MineControl;
import dev.minecontrol.sdk.CommandException;
import dev.minecontrol.sdk.Snapshots.Position;
import java.util.*;
import java.nio.file.*;

/** While FishboneMining runs, check once per 100 blocks of three-dimensional displacement. */
public class MiningScheduler {
    static final String MINER = "fishbone-mining.jar";
    static final String DETECTOR = "block-detector.jar";
    static final class Restarts {
        final Map<String,Deque<Long>> attempts=new HashMap<>();
        int minerRetries;
        boolean routeReset;
        void minerHealthy() { minerRetries=0;routeReset=false; }
        int attempt(String file,long now) {
            var times=attempts.computeIfAbsent(file,k->new ArrayDeque<>());
            while(!times.isEmpty() && now-times.peekFirst()>=10_000_000_000L) times.removeFirst();
            times.addLast(now);return times.size();
        }
    }
    static final class RecoveryProgress {
        Position baseline;
        long since;
        void reset() { baseline=null;since=0; }
        boolean healthy(Position current,boolean pendingUnload,long now) {
            if(pendingUnload) { reset();return false; }
            if(baseline==null || !Objects.equals(baseline.dimension(),current.dimension())) {
                baseline=current;since=now;return false;
            }
            return now-since>=10_000_000_000L &&
                    Math.hypot(current.x()-baseline.x(),current.z()-baseline.z())>=1;
        }
    }
    static Path resetRoute(Path directory) throws Exception {
        if(Files.exists(directory.resolve("fishbone-unload-state.json"))) return null;
        Path route=directory.resolve("fishbone-route-state.json");
        if(!Files.exists(route)) return route;
        Path backup=directory.resolve("fishbone-route-state.reset-"+UUID.randomUUID()+".json.bak");
        Files.move(route,backup,StandardCopyOption.ATOMIC_MOVE);
        return backup;
    }
    static void restart(MineControl mc,Restarts restarts,String file) throws Exception {
        int count=restarts.attempt(file,System.nanoTime());
        if(count>5) {
            String reason="重启频率超限："+file+" 在10秒内重启尝试超过5次，停止全部宏";
            System.err.println(reason);mc.exitReason(reason);mc.pauseMacros(reason);
            throw new IllegalStateException(reason);
        }
        if(MINER.equals(file) && ++restarts.minerRetries>=5 && !restarts.routeReset) {
            Path backup=resetRoute(Path.of("."));
            if(backup!=null) {
                restarts.routeReset=true;
                System.err.println("连续4次重启仍未恢复，已重置路线保存点；旧记录："+backup.toAbsolutePath());
            } else System.out.println("存在卸货返回记录，暂不重置路线保存点");
        }
        System.out.println("尝试重启 "+file+"，最近10秒第"+count+"次");
        try { mc.startMacro(file); }
        catch(CommandException e) {
            if(Set.of("STOPPING","SYSTEM_DISABLED").contains(e.code())) throw e;
            System.err.println("重启失败："+file+"："+e.getMessage());
        }
    }
    static boolean mining(MineControl.MacroInstance macro) {
        return !macro.stopping() && "execution".equals(macro.role())
                && ("fishbone-mining.jar".equals(macro.file()) || "FishboneMining.java".equals(macro.file()));
    }
    static boolean readyForMining(dev.minecontrol.sdk.Snapshots.Observation observation) {
        return observation!=null && observation.worldLoaded() && observation.active()
                && observation.player()!=null && observation.player().alive()
                && "survival".equals(observation.player().gameMode());
    }
    static boolean moved100(Position from, Position to) {
        if (!Objects.equals(from.dimension(),to.dimension())) return false;
        double x=to.x()-from.x(), y=to.y()-from.y(), z=to.z()-from.z();
        return x*x+y*y+z*z >= 10000;
    }
    static void stopOwned(MineControl mc, List<MineControl.MacroInstance> macros, String owned) throws Exception {
        if (owned != null) for (var macro:macros)
            if (owned.equals(macro.instance_id()) && !macro.stopping()) mc.stopMacro(owned,"自动挖矿已结束，停止关联探测");
    }
    static void run(MineControl mc) throws Exception {
        String owned=null, miningRun=null;
        boolean startup=true;
        var restarts=new Restarts();
        boolean retryDetector=false;
        var recoveryProgress=new RecoveryProgress();
        Position lastCheck=null;
        while (!mc.stopRequested()) {
            List<MineControl.MacroInstance> macros;
            try { macros=mc.macros(); }
            catch (CommandException e) {
                if (e.code().equals("FORBIDDEN") && mc.stopRequested()) break;
                throw e;
            }
            var miner=macros.stream().filter(MiningScheduler::mining).findFirst();
            if(mc.stopRequested()) break;
            for(var message:mc.pollBroadcasts().messages()) {
                if(!"$system".equals(message.channel()) || !message.data().isJsonObject()) continue;
                var data=message.data().getAsJsonObject();
                if(owned!=null && data.has("event") && "exited".equals(data.get("event").getAsString())
                        && owned.equals(data.get("instance_id").getAsString())) {
                    retryDetector=!"检查完成".equals(data.get("reason").getAsString());owned=null;
                }
            }
            if(miner.isEmpty()) {
                try {
                    if(!mc.status().worldLoaded() || !readyForMining(mc.observe())) { Thread.sleep(200);continue; }
                } catch(CommandException error) {
                    if(Set.of("WORLD_LOADING","NO_WORLD").contains(error.code())) { Thread.sleep(200);continue; }
                    throw error;
                }
            }
            if (startup) {
                startup=false;
                if (miner.isEmpty()) {
                    try {
                        mc.startMacro(MINER);
                        System.out.println("调度启动，已启动自动挖矿宏");
                    } catch (CommandException e) {
                        if(Set.of("STOPPING","SYSTEM_DISABLED").contains(e.code())) throw e;
                        System.err.println("挖矿启动失败，将尝试重启："+e.getMessage());
                    }
                    Thread.sleep(200);
                    continue;
                }
            }
            if (miner.isEmpty()) {
                recoveryProgress.reset();
                stopOwned(mc,macros,owned); owned=null; miningRun=null;
                if(mc.status().worldLoaded() && macros.stream().noneMatch(m -> MINER.equals(m.file()) || "FishboneMining.java".equals(m.file())))
                    restart(mc,restarts,MINER);
            } else {
                if (!Objects.equals(miningRun,miner.get().instance_id())) {
                    recoveryProgress.reset();
                    // A miner restart does not undo player displacement since the last scan.
                    stopOwned(mc,macros,owned); owned=null;
                    miningRun=miner.get().instance_id();
                }
                if (!mc.status().worldLoaded()) { lastCheck=null; Thread.sleep(200); continue; }
                // The miner writes this before teleporting and removes it only after returning.
                if(Files.exists(Path.of("fishbone-unload-state.json"))) { recoveryProgress.reset();Thread.sleep(200);continue; }
                Position current=mc.position();
                if(Files.exists(Path.of("fishbone-unload-state.json"))) { recoveryProgress.reset();Thread.sleep(200);continue; }
                if(recoveryProgress.healthy(current,false,System.nanoTime())) restarts.minerHealthy();
                if (lastCheck==null || !Objects.equals(lastCheck.dimension(),current.dimension())) {
                    lastCheck=current;
                }
                if ((retryDetector || moved100(lastCheck,current)) && macros.stream().noneMatch(m -> DETECTOR.equals(m.file()))) {
                    try {
                        if(retryDetector) restart(mc,restarts,DETECTOR);else mc.startMacro(DETECTOR);
                        lastCheck=current;
                        owned=mc.macros().stream().filter(m -> DETECTOR.equals(m.file()))
                                .map(MineControl.MacroInstance::instance_id).findFirst().orElse(null);
                        retryDetector=owned==null;
                        System.out.println("玩家三维位移达到 100 格，启动单次结构线索检查");
                    } catch (CommandException e) {
                        if (e.code().equals("STOPPING") || e.code().equals("SYSTEM_DISABLED")) throw e;
                        retryDetector=true;
                        System.err.println("探测启动失败，将尝试重启："+e.getMessage());
                    }
                }
            }
            Thread.sleep(200);
        }
        mc.exitReason("调度已停止");
    }
    public static void main(String[] args) throws Exception {
        try (var mc=MineControl.connect(args.length==0?25580:Integer.parseInt(args[0]),
                MineControl.MacroRole.SCHEDULER,"挖矿与结构线索探测调度")) { run(mc); }
        catch (CommandException e) {
            if (!e.code().equals("STOPPING") && !e.code().equals("SYSTEM_DISABLED")) throw e;
        }
    }
}
