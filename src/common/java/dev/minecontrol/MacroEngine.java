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
    private static final Set<String> COMMANDS = Set.of("observe", "ping", "status", "key.tap", "key.down", "key.up", "key.hold", "mouse.click", "mouse.down", "mouse.up", "mouse.hold", "mouse.goto", "mouse.move", "ui.list", "ui.click", "ui.set", "ui.type", "wait.screen");
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
    private static void validateCondition(JsonObject c) { validateCondition(c, 0); }
    private static void validateCondition(JsonObject c, int depth) {
        if (depth > 32) throw new IllegalArgumentException("Condition nesting exceeds 32");
        if (c == null) throw new IllegalArgumentException("Condition required");
        for (String group : List.of("all", "any", "not")) {
            if (!c.has(group)) continue;
            if (c.size() != 1) throw new IllegalArgumentException("Combination condition must have one key");
            if (group.equals("not")) validateCondition(c.getAsJsonObject(group), depth + 1);
            else {
                var children = c.getAsJsonArray(group);
                if (children.isEmpty()) throw new IllegalArgumentException("Empty condition group");
                for (var child : children) validateCondition(child.getAsJsonObject(), depth + 1);
            }
            return;
        }
        if (!c.has("field")) throw new IllegalArgumentException("Condition needs field");
        var field = c.get("field");
        if (field.isJsonArray()) {
            if (field.getAsJsonArray().isEmpty()) throw new IllegalArgumentException("Empty field path");
            for (var part : field.getAsJsonArray()) {
                if (!part.isJsonPrimitive() || !part.getAsJsonPrimitive().isString() || part.getAsString().isEmpty())
                    throw new IllegalArgumentException("Path segments must be nonempty strings (array indexes use strings)");
            }
        } else if (!field.isJsonPrimitive() || !field.getAsJsonPrimitive().isString() || field.getAsString().isEmpty())
            throw new IllegalArgumentException("field must be a string or path array");
        String op = c.has("op") ? c.get("op").getAsString() : "==";
        if (!Set.of("==", "!=", "<", "<=", ">", ">=", "exists").contains(op)) throw new IllegalArgumentException("Invalid comparison");
        if (op.equals("exists")) {
            if (!c.has("value") || !c.get("value").isJsonPrimitive() || !c.getAsJsonPrimitive("value").isBoolean())
                throw new IllegalArgumentException("exists requires boolean value");
        } else if (!c.has("value") || (!c.get("value").isJsonNull() && !c.get("value").isJsonPrimitive()))
            throw new IllegalArgumentException("Comparison value must be scalar");
    }
    private static boolean condition(JsonObject c, JsonObject status) {
        if (c.has("all")) { for (var child : c.getAsJsonArray("all")) if (!condition(child.getAsJsonObject(), status)) return false; return true; }
        if (c.has("any")) { for (var child : c.getAsJsonArray("any")) if (condition(child.getAsJsonObject(), status)) return true; return false; }
        if (c.has("not")) return !condition(c.getAsJsonObject("not"), status);
        var path = new ArrayList<String>();
        if (c.get("field").isJsonArray()) for (var part : c.getAsJsonArray("field")) path.add(part.getAsString());
        else path.addAll(Arrays.asList(c.get("field").getAsString().split("\\.", -1)));
        if (!status.has(path.get(0))) throw new IllegalArgumentException("Unknown status field: " + path.get(0));
        JsonElement a = status;
        for (String part : path) {
            if (a != null && a.isJsonObject()) a = a.getAsJsonObject().get(part);
            else if (a != null && a.isJsonArray() && part.matches("0|[1-9][0-9]{0,8}")) {
                int index = Integer.parseInt(part); a = index < a.getAsJsonArray().size() ? a.getAsJsonArray().get(index) : null;
            } else a = null;
        }
        String comparison = c.has("op") ? c.get("op").getAsString() : "==";
        if (comparison.equals("exists")) return (a != null) == c.get("value").getAsBoolean();
        if (a == null) return false;
        JsonElement b = c.get("value");
        String op = c.has("op") ? c.get("op").getAsString() : "==";
        if (op.equals("==")) return a.equals(b);
        if (op.equals("!=")) return !a.equals(b);
        if (!a.isJsonPrimitive() || !a.getAsJsonPrimitive().isNumber() || !b.isJsonPrimitive() || !b.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Ordered comparison requires numbers");
        int order = new java.math.BigDecimal(a.getAsString()).compareTo(new java.math.BigDecimal(b.getAsString()));
        return switch (op) { case "<" -> order < 0; case "<=" -> order <= 0; case ">" -> order > 0; default -> order >= 0; };
    }
}
