package dev.minecontrol;

import com.google.gson.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.*;

/** Tick-driven, bounded interpreter. All methods run on the client thread. */
public final class MacroEngine {
    private record Op(String kind, JsonObject data, int target) {}
    private final List<Op> program = new ArrayList<>();
    private final Map<Integer, Integer> counters = new HashMap<>();
    private String state = "idle", error;
    private int pc;
    private long wake, steps;
    private CompletableFuture<JsonObject> pending;
    private final Runnable release;
    public MacroEngine(Runnable release) { this.release = release; }
    public JsonObject status() {
        var r = new JsonObject(); r.addProperty("state", state); r.addProperty("instruction", pc);
        r.addProperty("steps", steps); if (error != null) r.addProperty("error", error); return r;
    }
    public void start(JsonArray script) {
        if (state.equals("running")) throw new IllegalStateException("A macro is already running; stop it first");
        var compiled = new ArrayList<Op>(); try { compile(script, compiled, 0, -1); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Invalid macro: " + e.getMessage(), e); }
        program.clear(); program.addAll(compiled); counters.clear(); pc = 0; wake = 0; steps = 0;
        pending = null; error = null; state = "running";
    }
    public void stop() { if (state.equals("running")) finish("stopped", null); }
    private void finish(String next, String message) {
        state = next; error = message;
        if (pending != null) pending.cancel(false);
        pending = null; counters.clear(); release.run();
    }
    public void tick(long tick, Supplier<JsonObject> snapshot, Function<JsonObject, CompletableFuture<JsonObject>> execute) {
        if (!state.equals("running")) return;
        try {
            if (pending != null) {
                if (!pending.isDone()) return;
                JsonObject response = pending.join(); pending = null;
                if (!response.get("ok").getAsBoolean()) throw new IllegalArgumentException(response.get("error").toString());
            }
            if (tick < wake) return;
            if (pc >= program.size()) { finish("completed", null); return; }
            Op op = program.get(pc); steps++;
            switch (op.kind) {
                case "command" -> { var request = op.data.deepCopy(); request.addProperty("id", "macro"); pending = execute.apply(request); pc++; }
                case "wait" -> { wake = tick + count(op.data.get("wait"), 1, 1000000); pc++; }
                case "if", "while" -> pc = condition(op.data.getAsJsonObject(op.kind), snapshot.get()) ? pc + 1 : op.target;
                case "repeat" -> {
                    int left = counters.computeIfAbsent(pc, k -> count(op.data.get("repeat"), 0, 1000000));
                    if (left == 0) { counters.remove(pc); pc = op.target; }
                    else { counters.put(pc, left - 1); pc++; }
                }
                case "jump" -> pc = op.target;
                case "break" -> { counters.keySet().removeIf(k -> k >= op.target && k <= pc); pc = program.get(op.target).target; }
                default -> throw new IllegalStateException("Invalid instruction");
            }
        } catch (Exception e) { finish("failed", e.getMessage()); }
    }
    private static final Set<String> COMMANDS = Set.of("ping", "status", "key.tap", "key.down", "key.up", "key.hold", "mouse.click", "mouse.down", "mouse.up", "mouse.hold", "mouse.goto", "mouse.move", "ui.list", "ui.click", "ui.set", "ui.type", "wait.screen");
    private static void compile(JsonArray body, List<Op> out, int depth, int loop) {
        if (depth > 32) throw new IllegalArgumentException("Macro nesting exceeds 32");
        for (JsonElement value : body) {
            if (out.size() >= 4096) throw new IllegalArgumentException("Macro exceeds 4096 instructions");
            JsonObject o = value.getAsJsonObject();
            long kinds = List.of("command", "wait", "repeat", "while", "if", "break").stream().filter(o::has).count();
            if (kinds != 1) throw new IllegalArgumentException("Each step needs exactly one command/wait/repeat/while/if/break");
            if (o.has("command")) {
                if (!COMMANDS.contains(o.get("command").getAsString())) throw new IllegalArgumentException("Unsupported macro command");
                out.add(new Op("command", o.deepCopy(), -1));
            } else if (o.has("wait")) {
                count(o.get("wait"), 1, 1000000); out.add(new Op("wait", o.deepCopy(), -1));
            } else if (o.has("break")) {
                if (loop < 0 || !o.get("break").isJsonPrimitive() || !o.get("break").getAsJsonPrimitive().isBoolean() || !o.get("break").getAsBoolean()) throw new IllegalArgumentException("break:true requires a loop");
                out.add(new Op("break", o, loop));
            } else {
                String kind = o.has("repeat") ? "repeat" : o.has("while") ? "while" : "if";
                if (kind.equals("repeat")) count(o.get(kind), 0, 1000000); else validateCondition(o.getAsJsonObject(kind));
                if (!kind.equals("if") && o.has("else")) throw new IllegalArgumentException("else requires if");
                int head = out.size(); out.add(new Op(kind, o.deepCopy(), -1));
                compile(o.getAsJsonArray(kind.equals("if") ? "then" : "do"), out, depth + 1, kind.equals("if") ? loop : head);
                if (kind.equals("if")) {
                    int jump = out.size(); out.add(new Op("jump", o, -1));
                    out.set(head, new Op(kind, o.deepCopy(), out.size()));
                    if (o.has("else")) compile(o.getAsJsonArray("else"), out, depth + 1, loop);
                    out.set(jump, new Op("jump", o, out.size()));
                } else {
                    out.add(new Op("jump", o, head)); out.set(head, new Op(kind, o.deepCopy(), out.size()));
                }
            }
        }
        if (out.size() > 4096) throw new IllegalArgumentException("Macro exceeds 4096 instructions");
    }
    private static int count(JsonElement e, int min, int max) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Expected integer");
        double n = e.getAsDouble();
        if (!Double.isFinite(n) || n != Math.rint(n) || n < min || n > max) throw new IllegalArgumentException("Integer must be " + min + ".." + max);
        return (int)n;
    }
    private static void validateCondition(JsonObject c) {
        if (c == null || !c.has("field") || !c.get("field").isJsonPrimitive() || !c.getAsJsonPrimitive("field").isString() || !c.has("value")) throw new IllegalArgumentException("Condition needs field and value");
        String op = c.has("op") ? c.get("op").getAsString() : "==";
        if (!Set.of("==", "!=", "<", "<=", ">", ">=").contains(op)) throw new IllegalArgumentException("Invalid comparison");
        if (!c.get("value").isJsonNull() && !c.get("value").isJsonPrimitive()) throw new IllegalArgumentException("Comparison value must be scalar");
    }
    private static boolean condition(JsonObject c, JsonObject status) {
        String field = c.get("field").getAsString();
        if (!status.has(field)) throw new IllegalArgumentException("Unknown status field: " + field);
        JsonElement a = status.get(field), b = c.get("value");
        String op = c.has("op") ? c.get("op").getAsString() : "==";
        if (op.equals("==")) return a.equals(b);
        if (op.equals("!=")) return !a.equals(b);
        if (!a.isJsonPrimitive() || !a.getAsJsonPrimitive().isNumber() || !b.isJsonPrimitive() || !b.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Ordered comparison requires numbers");
        double x = a.getAsDouble(), y = b.getAsDouble();
        return switch (op) { case "<" -> x < y; case "<=" -> x <= y; case ">" -> x > y; default -> x >= y; };
    }
}
