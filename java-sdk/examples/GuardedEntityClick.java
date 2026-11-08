import dev.minecontrol.sdk.MineControl;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/** Entity clicks pause for nearby players and resume only after the return check is clear. */
public class GuardedEntityClick {
    interface Timer {
        long nanoTime();
        void sleep(long millis) throws InterruptedException;
    }
    public static void main(String[] args) throws Exception {
        int port = args.length == 0 ? 25580 : Integer.parseInt(args[0]);
        try (var mc = MineControl.connect(port)) {
            run(mc, new Timer() {
                public long nanoTime() { return System.nanoTime(); }
                public void sleep(long millis) throws InterruptedException { Thread.sleep(millis); }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    static void run(MineControl mc, Timer timer) throws Exception {
        long clickAt = -1, clearSince = -1;
        boolean avoiding = false;
        System.out.println("自动点击 + 10 格玩家警戒：有玩家时 /home out，持续 20 tick 无玩家后 /home work 并恢复点击。");
        while (!Thread.currentThread().isInterrupted()) {
            var status = mc.status();
            if (!status.worldLoaded()) {
                clickAt = -1;
                clearSince = -1;
                if (!awaitWorld(mc, timer)) return;
                continue;
            }
            var players = mc.nearbyPlayers(10);
            if (!players.isEmpty()) {
                clickAt = -1;
                clearSince = -1;
                if (!avoiding) {
                    System.out.println("发现玩家：" + players.get(0).name() + "，取消攻击并执行 /home out。");
                    mc.resetInputs();
                    mc.sendCommand("/home out");
                    avoiding = true;
                }
                mc.waitTicks(1);
                continue;
            }
            if (avoiding) {
                if (clearSince < 0) clearSince = mc.status().tick();
                if (status.tick() - clearSince >= 20) {
                    mc.sendCommand("/home work");
                    System.out.println("周围持续 20 tick 无玩家，/home work 已发送，恢复自动点击与玩家警戒。");
                    avoiding = false;
                    clearSince = -1;
                    clickAt = -1;
                }
                mc.waitTicks(1);
                continue;
            }
            if (clickAt >= 0) {
                if (status.tick() >= clickAt) {
                    mc.mouse().click(MineControl.MouseButton.LEFT);
                    clickAt = -1;
                }
            } else {
                var observation = mc.observe();
                if (observation.active() && observation.target() != null
                        && "entity".equals(observation.target().kind())) {
                    int delay = ThreadLocalRandom.current().nextInt(10, 31);
                    clickAt = mc.status().tick() + delay;
                    System.out.println("检测到实体，等待 " + delay + " tick；等待期间持续警戒玩家。");
                }
            }
            mc.waitTicks(1);
        }
    }
    private static boolean awaitWorld(MineControl mc, Timer timer) throws Exception {
        long start = timer.nanoTime();
        System.out.println("世界正在加载，暂停操作，最多等待 30 秒…");
        while (!mc.status().worldLoaded()) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (timer.nanoTime() - start >= Duration.ofSeconds(30).toNanos()) return false;
            timer.sleep(100);
        }
        return true;
    }
}
