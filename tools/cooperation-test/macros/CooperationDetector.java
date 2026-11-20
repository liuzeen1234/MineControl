import dev.minecontrol.sdk.*;

/** Read-only actor: verifies permissions, then broadcasts real game snapshots. */
public final class CooperationDetector {
    public static void main(String[] args) throws Exception {
        try (var mc = MineControl.connect(args.length == 0 ? 25580 : Integer.parseInt(args[0]), MineControl.MacroRole.DETECTION, "Cooperation detector")) {
            CooperationSupport.forbidden(() -> { try { mc.key().down("LEFT_SHIFT"); } catch (java.io.IOException e) { throw new RuntimeException(e); } });
            var ready = CooperationSupport.data("detector_ready"); ready.addProperty("permission_checked", true);
            mc.broadcast(CooperationSupport.EVENTS, ready);
            long deadline = CooperationSupport.deadline();
            try {
                while (!mc.stopRequested()) {
                    CooperationSupport.alive(deadline);
                    var status = mc.status(); var data = CooperationSupport.data("snapshot");
                    data.addProperty("world", status.worldLoaded()); data.addProperty("held", !status.heldKeys().isEmpty());
                    if (status.yaw() != null) data.addProperty("yaw", status.yaw());
                    data.addProperty("tick", status.tick());
                    mc.broadcast(CooperationSupport.SAMPLE, data); Thread.sleep(100);
                }
            } catch (CommandException stopped) {
                if (!stopped.code().equals("STOPPING")) throw stopped;
            }
            mc.exitReason("Cooperation detector stopped cleanly");
        }
    }
}
