import dev.minecontrol.sdk.MineControl;

/** Replace the status/logging section with your own game actions. */
public class BroadcastExecutor {
    public static void main(String[] args) throws Exception {
        int port = args.length == 0 ? 25580 : Integer.parseInt(args[0]);
        try (var mc = MineControl.connect(port, MineControl.MacroRole.EXECUTION, "广播执行示例")) {
            mc.subscribe("players.nearby");
            while (!mc.stopRequested()) {
                for (var message : mc.pollBroadcasts().messages()) {
                    if (message.channel().equals("players.nearby")) System.out.println("玩家检测结果：" + message.data());
                }
                // Add game actions here; only one execution macro can hold this role.
                Thread.sleep(100);
            }
            mc.exitReason("收到调度停止请求，执行结束");
        }
    }
}
