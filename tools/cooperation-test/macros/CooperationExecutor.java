import dev.minecontrol.sdk.*;

/** Input actor: holds only crouch, changes view once, and relies on stop cleanup. */
public final class CooperationExecutor {
    public static void main(String[] args) throws Exception {
        try (var mc = MineControl.connect(args.length == 0 ? 25580 : Integer.parseInt(args[0]), MineControl.MacroRole.EXECUTION, "Cooperation executor")) {
            mc.subscribe(CooperationSupport.COMMAND);
            mc.broadcast(CooperationSupport.EVENTS, CooperationSupport.data("executor_ready"));
            long deadline = CooperationSupport.deadline(); boolean held = false;
            try {
                while (!mc.stopRequested()) {
                    CooperationSupport.alive(deadline);
                    var mailbox = mc.pollBroadcasts(); CooperationSupport.require(mailbox.dropped() == 0, "Executor dropped messages");
                    for (var message : mailbox.messages()) {
                        if (message.channel().equals(CooperationSupport.COMMAND) && message.data().getAsJsonObject().get("type").getAsString().equals("hold") && !held) {
                            var before = mc.status(); CooperationSupport.require(before.worldLoaded(), "No test world");
                            mc.key().down("LEFT_SHIFT"); mc.mouse().move(10, 0); held = true;
                            var ack = CooperationSupport.data("held"); ack.addProperty("before_yaw", before.yaw());
                            ack.addProperty("after_yaw", mc.status().yaw()); mc.broadcast(CooperationSupport.EVENTS, ack);
                        }
                    }
                    Thread.sleep(50);
                }
            } catch (CommandException stopped) {
                if (!stopped.code().equals("STOPPING")) throw stopped;
            }
            mc.exitReason("Cooperation executor stopped cleanly");
        }
    }
}
