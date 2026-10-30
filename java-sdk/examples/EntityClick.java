import dev.minecontrol.sdk.MineControl;
import java.util.concurrent.ThreadLocalRandom;

/** Repeatedly click while aiming at entities; do not recheck after each random delay. */
public class EntityClick {
    public static void main(String[] args) throws Exception {
        int port = args.length == 0 ? 25580 : Integer.parseInt(args[0]);
        try (var mc = MineControl.connect(port)) {
            System.out.println("持续瞄准实体时，反复随机等待 10–30 tick 后左键打击。Ctrl+C 停止。");
            while (!Thread.currentThread().isInterrupted()) {
                var observation = mc.observe();
                boolean entityVisible = observation.active()
                        && observation.target() != null
                        && "entity".equals(observation.target().kind());

                if (entityVisible) {
                    int delay = ThreadLocalRandom.current().nextInt(10, 31);
                    System.out.println("检测到实体，等待 " + delay + " tick。");
                    mc.waitTicks(delay);
                    mc.mouse().click(MineControl.MouseButton.LEFT);
                    System.out.println("已点击左键。");
                }
                mc.waitTicks(1);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
