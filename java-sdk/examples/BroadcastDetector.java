import dev.minecontrol.sdk.MineControl;
import com.google.gson.JsonObject;

/** Read-only detector. Run alongside BroadcastExecutor and MacroScheduler. */
public class BroadcastDetector {
    public static void main(String[] args) throws Exception {
        int port = args.length == 0 ? 25580 : Integer.parseInt(args[0]);
        try (var mc = MineControl.connect(port, MineControl.MacroRole.DETECTION, "附近玩家检测")) {
            while (!mc.stopRequested()) {
                if (mc.status().worldLoaded()) {
                    var players = mc.nearbyPlayers(10);
                    var data = new JsonObject();
                    data.addProperty("nearby", !players.isEmpty()); data.addProperty("count", players.size());
                    mc.broadcast("players.nearby", data);
                }
                Thread.sleep(500);
            }
            mc.exitReason("收到管理器停止请求，检测结束");
        }
    }
}
