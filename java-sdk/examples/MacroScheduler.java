import dev.minecontrol.sdk.MineControl;

/** Imports of the two example .java files must exist in the game's macro directory. */
public class MacroScheduler {
    public static void main(String[] args) throws Exception {
        int port = args.length == 0 ? 25580 : Integer.parseInt(args[0]);
        try (var mc = MineControl.connect(port, MineControl.MacroRole.SCHEDULER, "玩家警戒调度")) {
            mc.subscribe("players.nearby");
            mc.startMacro("BroadcastDetector.java"); mc.startMacro("BroadcastExecutor.java");
            while (!mc.stopRequested()) {
                var mailbox = mc.pollBroadcasts();
                if (mailbox.dropped() > 0) {
                    mc.pauseMacros("广播积压，暂停并等待用户检查");
                    mc.exitReason("广播积压，调度已停止"); return;
                }
                for (var message : mailbox.messages()) {
                    if (message.channel().equals("players.nearby") && message.data().getAsJsonObject().get("nearby").getAsBoolean()) {
                        for (var macro : mc.macros()) if (macro.role().equals("execution") && !macro.stopping())
                            mc.stopMacro(macro.instance_id(), "附近有玩家，停止执行宏");
                    }
                    if (message.channel().equals("$system")) System.out.println("宏生命周期：" + message.data());
                }
                Thread.sleep(100);
            }
            mc.exitReason("收到用户停止请求，调度结束");
        }
    }
}
