import dev.minecontrol.sdk.MineControl;

/** Run only after entering a world and choosing a clear place to walk. */
public class Walk {
    public static void main(String[] args) throws Exception {
        try (var mc = MineControl.connect()) {
            if (!mc.observe().active()) throw new IllegalStateException("请进入世界并关闭 GUI");
            for (int i = 0; i < 3; i++) {
                mc.key().holdAndWait("w", 20);
                mc.waitTicks(5);
            }
            System.out.println(mc.position());
        }
    }
}
