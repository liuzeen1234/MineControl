package dev.minecontrol;

import com.google.gson.*;
import java.util.*;

public final class MacroCoordinatorTest {
    private static JsonObject request(String command, String token, Object... pairs) {
        JsonObject r = new JsonObject(); r.addProperty("command", command);
        if (token != null) r.addProperty("macro_token", token);
        for (int i = 0; i < pairs.length; i += 2) r.add((String)pairs[i], new Gson().toJsonTree(pairs[i + 1]));
        return r;
    }
    private static String register(MacroCoordinator c, String role, String name) {
        return c.handle(request("macro.register", null, "role", role, "name", name), false)
                .getAsJsonObject().get("macro_token").getAsString();
    }
    private static JsonArray poll(MacroCoordinator c, String token) {
        return c.handle(request("bus.poll", token), false).getAsJsonObject().getAsJsonArray("messages");
    }
    private static void denied(String code, Runnable action) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (MacroCoordinator.Error e) { check(e.code.equals(code), e.code + ": " + e.getMessage()); }
    }
    private static void check(boolean yes, String message) { if (!yes) throw new AssertionError(message); }
    public static void run() {
        int[] resets = {0}; boolean[] json = {false};
        var c = new MacroCoordinator(() -> resets[0]++, () -> json[0]);
        String detector = register(c, "detection", "玩家检测");
        String other = register(c, "detection", "血量检测");
        check(!c.executing(), "detectors leave physical input available");
        denied("FORBIDDEN", () -> c.handle(request("key.down", detector), false));
        denied("FORBIDDEN", () -> c.handle(request("input.reset", detector), false));
        denied("FORBIDDEN", () -> c.handle(request("chat.command", detector), false));
        denied("FORBIDDEN", () -> c.handle(request("program.start", detector), false));
        String execution = register(c, "execution", "挖矿");
        denied("MACRO_BUSY", () -> register(c, "execution", "第二个执行宏"));
        denied("MACRO_BUSY", () -> c.handle(request("macro.run", null), false));
        denied("MACRO_BUSY", () -> c.handle(request("key.down", null), false));
        check(c.handle(request("key.down", execution), false) == null, "executor authorized");
        String scheduler = register(c, "scheduler", "调度");
        denied("MACRO_BUSY", () -> register(c, "scheduler", "第二个调度宏"));
        denied("FORBIDDEN", () -> c.handle(request("mouse.click", scheduler), false));
        c.handle(request("bus.subscribe", other, "channel", "danger"), false);
        poll(c, other); poll(c, detector);
        c.handle(request("bus.publish", detector, "channel", "danger", "data", Map.of("player", "Alex")), false);
        JsonArray delivered = poll(c, other);
        check(delivered.size() == 1 && delivered.get(0).getAsJsonObject().get("sender").getAsString().equals("玩家检测"), "channel and sender");
        check(poll(c, detector).isEmpty(), "unsubscribed channel excluded");
        denied("FORBIDDEN", () -> c.handle(request("bus.publish", detector, "channel", "$system", "data", "fake"), false));
        for (int i = 0; i < 140; i++) c.handle(request("bus.publish", detector, "channel", "danger", "data", i), false);
        JsonObject mailbox = c.handle(request("bus.poll", other), false).getAsJsonObject();
        check(mailbox.getAsJsonArray("messages").size() == 128 && mailbox.get("dropped").getAsInt() == 12, "bounded mailbox reports overflow");
        int before = resets[0];
        c.handle(request("macro.exit", detector, "reason", "检测完成"), false);
        check(resets[0] == before && c.executing(), "detector exit cannot release executor input");
        JsonObject event = poll(c, other).get(0).getAsJsonObject().getAsJsonObject("data");
        check(event.get("name").getAsString().equals("玩家检测") && event.get("reason").getAsString().equals("检测完成"), "exit name and reason broadcast");
        c.handle(request("macro.exit", execution, "reason", "挖矿完成"), false);
        check(!c.executing() && resets[0] == before + 1, "executor cleanup before slot released");
        denied("NOT_REGISTERED", () -> c.handle(request("key.down", execution), false));
        json[0] = true; denied("MACRO_BUSY", () -> register(c, "execution", "JSON conflict")); json[0] = false;
        c.handle(request("system.pause", scheduler, "reason", "暂时休息"), false);
        denied("SYSTEM_DISABLED", () -> register(c, "detection", "paused"));
        check(c.handle(request("macro.heartbeat", other), false).getAsJsonObject().get("stopping").getAsBoolean(), "pause sends stop request");
        denied("STOPPING", () -> c.handle(request("bus.publish", other, "channel", "danger", "data", 1), false));
        c.handle(request("system.resume", scheduler), false);
        String token = c.prepare("Legacy.java");
        check(c.handle(request("ping", token), false) == null && !c.executing(), "ping before role declaration");
        c.handle(request("status", token), false); check(c.executing(), "legacy program defaults to execution");
        poll(c, scheduler);
        c.processExited(token, "进程异常退出：1"); c.processExited(token, "duplicate"); c.tick();
        JsonArray events = poll(c, scheduler);
        check(events.size() == 1 && events.get(0).getAsJsonObject().getAsJsonObject("data").get("name").getAsString().equals("Legacy.java"), "crash exit broadcast once");
        String pending = c.prepare("Waiting.java");
        c.handle(request("macro.register", pending, "name", "Waiting", "role", "detection"), false);
        c.handle(request("macro.exit", scheduler, "reason", "调度结束"), false);
        check(c.handle(request("macro.heartbeat", pending), false).getAsJsonObject().get("stopping").getAsBoolean(), "scheduler exit stops remaining programs");
        String managed = c.prepare("Managed.java");
        // The scheduler just stopped old business sessions; a new one can still register while enabled.
        c.handle(request("macro.register", managed, "name", "Managed", "role", "execution"), false);
        c.handle(request("macro.exit", managed, "reason", "正常结束", "reason_explicit", false), false);
        check(c.executing() && !c.controlling(), "managed execution retains admission slot but releases input capture until process actually exits");
        denied("STOPPING", () -> c.handle(request("key.down", managed), false));
        denied("MACRO_BUSY", () -> register(c, "execution", "early replacement"));
        String watcher = register(c, "detection", "crash watcher"); poll(c, watcher);
        c.processExited(managed, "主线程异常退出", "failed"); c.tick();
        JsonObject failure = poll(c, watcher).get(0).getAsJsonObject().getAsJsonObject("data");
        check(failure.get("exit_type").getAsString().equals("failed") && failure.get("reason").getAsString().equals("主线程异常退出"),
                "process failure overrides the SDK default shutdown reason");
        c.emergency("F9"); denied("SYSTEM_DISABLED", () -> register(c, "execution", "automatic restart"));
        c.resume(); register(c, "execution", "manual restart");
        System.out.println("Macro registration/roles/admission/broadcast/exit/scheduling/emergency checks passed");
    }
}
