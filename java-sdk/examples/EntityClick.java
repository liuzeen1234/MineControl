import dev.minecontrol.sdk.MineControl;
import java.util.concurrent.ThreadLocalRandom;

/** Click once after an entity appears under the crosshair, without rechecking after the delay. */
public class EntityClick {
    public static void main(String[] args) throws Exception {
        int port = args.length == 0 ? 25580 : Integer.parseInt(args[0]);
        try (var mc = MineControl.connect(port)) {
            boolean entityPreviouslyVisible = false;
            System.out.println("监听准星实体；出现后随机等待 10–30 tick 点击一次左键。Ctrl+C 停止。");
            while (!Thread.currentThread().isInterrupted()) {
                var observation = mc.observe();
                boolean entityVisible = observation.active()
                        && observation.target() != null
                        && "entity".equals(observation.target().kind());

                if (entityVisible && !entityPreviouslyVisible) {
                    int delay = ThreadLocalRandom.current().nextInt(10, 31);
                    System.out.println("检测到实体，等待 " + delay + " tick。");
                    mc.waitTicks(delay);
                    mc.mouse().click(MineControl.MouseButton.LEFT);
                }
                entityPreviouslyVisible = entityVisible;
                mc.waitTicks(1);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
