import dev.minecontrol.sdk.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Runs normal/restart, pause/resume, or scheduler-exit cascade scenarios. */
public final class CooperationScheduler {
    private final List<String> checks = new ArrayList<>();
    private final List<JsonObject> lifecycle = new ArrayList<>();
    private final Set<String> events = new HashSet<>();
    private MineControl mc;
    private JsonObject snapshot, held;
    private final long deadline = CooperationSupport.deadline();
    public static void main(String[] args) throws Exception { new CooperationScheduler().run(args.length == 0 ? 25580 : Integer.parseInt(args[0])); }
    private void run(int port) throws Exception {
        String scenario = Files.exists(Path.of("cooperation-scenario.txt")) ? Files.readString(Path.of("cooperation-scenario.txt")).strip() : "normal";
        try (var connection = MineControl.connect(port, MineControl.MacroRole.SCHEDULER, "Cooperation scheduler")) {
            mc = connection; mc.subscribe(CooperationSupport.EVENTS); mc.subscribe(CooperationSupport.SAMPLE); mc.subscribe("$system");
            check("scheduler input is forbidden", () -> { CooperationSupport.forbidden(() -> { try { mc.key().down("LEFT_SHIFT"); } catch (java.io.IOException e) { throw new RuntimeException(e); } }); return true; });
            startPair(); hold();
            if (scenario.equals("normal")) {
                String old = role("execution").instance_id();
                stop("execution"); await(() -> missing("execution"));
                check("stopping executor releases held input", () -> mcStatusClean());
                check("detector continues after executor stop", () -> role("detection") != null);
                await(() -> lifecycle.stream().anyMatch(e -> e.get("event").getAsString().equals("exited") && e.get("role").getAsString().equals("execution")));
                check("executor exit lifecycle delivered", () -> true);
                events.remove("executor_ready"); held = null; snapshot = null;
                mc.startMacro("cooperation-executor.jar"); await(() -> events.contains("executor_ready"));
                check("restart uses new instance", () -> !old.equals(role("execution").instance_id())); hold();
                stop("execution"); stop("detection"); await(() -> missing("execution") && missing("detection"));
                check("normal stop removes both workers", () -> mcStatusClean());
            } else if (scenario.equals("pause")) {
                mc.pauseMacros("Cooperation pause checkpoint");
                await(() -> missing("execution") && missing("detection"));
                check("pause clears workers and input", () -> mcStatusClean());
                try { mc.startMacro("cooperation-detector.jar"); throw new AssertionError("Paused start accepted"); }
                catch (CommandException e) { check("paused system rejects new worker", () -> e.code().equals("START_FAILED")); }
                mc.resumeMacros(); events.clear(); snapshot = null; held = null; startPair(); hold();
                stop("execution"); stop("detection"); await(() -> missing("execution") && missing("detection"));
                check("resume restores cooperation", () -> mcStatusClean());
            } else if (!scenario.equals("cascade")) throw new AssertionError("Unknown scenario: " + scenario);
            mc.exitReason("Cooperation " + scenario + " complete");
            write(scenario, "passed", "");
        } catch (Throwable error) {
            write(scenario, "failed", error.toString()); throw error;
        }
    }
    private void startPair() throws Exception {
        mc.startMacro("cooperation-detector.jar"); mc.startMacro("cooperation-executor.jar");
        await(() -> events.containsAll(Set.of("detector_ready", "executor_ready")));
        check("all three roles registered", () -> role("execution") != null && role("detection") != null && role("scheduler") != null);
        check("detector input is forbidden", () -> true);
    }
    private void hold() throws Exception {
        mc.broadcast(CooperationSupport.COMMAND, CooperationSupport.data("hold"));
        await(() -> held != null && snapshot != null && snapshot.get("held").getAsBoolean() && snapshot.has("yaw") && Math.abs(snapshot.get("yaw").getAsDouble() - held.get("after_yaw").getAsDouble()) < .01);
        check("detector observes executor held input", () -> snapshot.get("world").getAsBoolean());
        check("executor changed actual game view", () -> Math.abs(held.get("after_yaw").getAsDouble() - held.get("before_yaw").getAsDouble()) > .1);
        check("detector observes changed view", () -> Math.abs(snapshot.get("yaw").getAsDouble() - held.get("after_yaw").getAsDouble()) < .01);
    }
    private MineControl.MacroInstance role(String role) {
        try { return mc.macros().stream().filter(m -> role.equals(m.role())).findFirst().orElse(null); }
        catch (java.io.IOException e) { throw new RuntimeException(e); }
    }
    private boolean missing(String role) { return role(role) == null; }
    private boolean mcStatusClean() { try { return mc.status().heldKeys().isEmpty() && mc.status().heldButtons().isEmpty(); } catch (Exception e) { throw new RuntimeException(e); } }
    private void stop(String role) throws Exception { var actor = role(role); if (actor != null) mc.stopMacro(actor.instance_id(), "Cooperation stop " + role); }
    private void await(BooleanSupplier condition) throws Exception {
        while (!condition.getAsBoolean()) {
            CooperationSupport.alive(deadline); CooperationSupport.require(!mc.stopRequested(), "Scheduler interrupted");
            var mailbox = mc.pollBroadcasts(); CooperationSupport.require(mailbox.dropped() == 0, "Scheduler dropped messages");
            for (var message : mailbox.messages()) {
                var data = message.data().getAsJsonObject();
                if (message.channel().equals("$system")) lifecycle.add(data);
                else if (message.channel().equals(CooperationSupport.SAMPLE)) snapshot = data;
                else if (message.channel().equals(CooperationSupport.EVENTS)) { events.add(data.get("type").getAsString()); if (data.get("type").getAsString().equals("held")) held = data; }
            }
            Thread.sleep(50);
        }
    }
    private void check(String label, BooleanSupplier condition) { CooperationSupport.require(condition.getAsBoolean(), label); checks.add(label); System.out.println("PASS " + label); }
    private void write(String scenario, String state, String error) throws Exception {
        Files.writeString(Path.of("cooperation-report-" + scenario + ".json"), CooperationSupport.JSON.toJson(Map.of("scenario", scenario, "state", state, "error", error, "checks", checks, "lifecycle", lifecycle)));
    }
}
