package dev.minecontrol;

import com.google.gson.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;

/** Admission, permissions and bounded mailboxes. Protocol calls execute on the game thread. */
public final class MacroCoordinator {
    public static final String SYSTEM = "$system";
    private static final Set<String> READ = Set.of("ping", "status", "position", "observe", "inventory",
            "players.nearby", "region.query", "ui.list", "wait.screen", "macro.status");
    private static final int MAX_SESSIONS = 128, MAX_MESSAGES = 128;
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private final Queue<Runnable> lifecycle = new ConcurrentLinkedQueue<>();
    private final Runnable cleanup;
    private final BooleanSupplier jsonRunning;
    private JavaMacroManager programs;
    private boolean enabled = true;
    private long sequence;
    private static final class Session {
        final String token, file, instance = UUID.randomUUID().toString();
        String name, role, reason = "", requestedBy = "", requestedReason = "";
        boolean stopping, closing, explicitReason;
        long seen = System.nanoTime(), stopAt;
        long dropped;
        final Set<String> channels = new HashSet<>();
        final Deque<JsonObject> messages = new ArrayDeque<>();
        Session(String token, String file) { this.token = token; this.file = file; this.name = file; }
    }
    public static final class Error extends RuntimeException {
        public final String code;
        Error(String code, String message) { super(message); this.code = code; }
    }
    private static Error error(String code, String message) { return new Error(code, message); }
    public MacroCoordinator(Runnable cleanup, BooleanSupplier jsonRunning) { this.cleanup = cleanup; this.jsonRunning = jsonRunning; }
    public synchronized void attach(JavaMacroManager programs) { this.programs = programs; }
    public synchronized String prepare(String file) {
        if (!enabled) throw error("SYSTEM_DISABLED", "宏系统已暂停，请手动恢复");
        if (sessions.size() >= MAX_SESSIONS) throw error("LIMIT", "Too many macro instances");
        String token = UUID.randomUUID().toString(); sessions.put(token, new Session(token, file)); return token;
    }
    public void processExited(String token, String reason) { processExited(token, reason, "process_exit"); }
    public void processExited(String token, String reason, String kind) { lifecycle.add(() -> finish(token, kind, reason)); }
    public synchronized void tick() {
        Runnable event; while ((event = lifecycle.poll()) != null) event.run();
        long now = System.nanoTime();
        for (Session s : List.copyOf(sessions.values())) {
            if (s.stopping && now >= s.stopAt && s.file == null) finish(s.token, "stopped", s.requestedReason);
            else if (s.file == null && now - s.seen > 30_000_000_000L) finish(s.token, "timeout", "宏心跳超时");
        }
    }
    public synchronized boolean controlling() {
        return sessions.values().stream().anyMatch(s -> "execution".equals(s.role) && !s.stopping && !s.closing);
    }
    public synchronized boolean executing() { return sessions.values().stream().anyMatch(s -> "execution".equals(s.role)); }
    public synchronized String fileState(String file) {
        return sessions.values().stream().filter(s -> Objects.equals(s.file, file))
                .map(s -> s.stopping || s.closing ? "正在停止" : s.role == null ? "等待注册" : switch (s.role) {
                    case "execution" -> "执行宏"; case "detection" -> "检测宏"; default -> "调度宏";
                }).findFirst().orElse(null);
    }
    public synchronized boolean active() { return !sessions.isEmpty(); }
    public synchronized String summary() {
        return String.join("、", sessions.values().stream().filter(s -> s.role != null).map(s -> s.name + " [" + s.role + "]").toList());
    }
    public synchronized void emergency(String reason) {
        enabled = false;
        for (Session s : List.copyOf(sessions.values())) requestStop(s, "user", reason);
        cleanup.run();
    }
    /** Fabric disconnect callbacks may run on Netty threads; cleanup belongs on the game thread. */
    public void enqueueWorldExit(String reason) { lifecycle.add(() -> worldExited(reason)); }
    /** A server session ended; schedulers continue running in the menu. */
    public synchronized void worldExited(String reason) {
        for (Session s : List.copyOf(sessions.values()))
            if (!"scheduler".equals(s.role)) requestStop(s, "world", reason);
        cleanup.run();
    }
    public synchronized void resume() { enabled = true; }
    public synchronized void stopFile(String file, String reason) {
        for (Session s : List.copyOf(sessions.values())) if (Objects.equals(file, s.file)) requestStop(s, "user", reason);
    }
    private void requestStop(Session s, String by, String reason) {
        if (s.stopping) return;
        s.stopping = true; s.requestedBy = by; s.requestedReason = reason;
        s.stopAt = System.nanoTime() + 3_000_000_000L;
        event("stop_requested", s, reason, "requested");
        if ("execution".equals(s.role)) cleanup.run();
        if (s.file != null && programs != null) programs.terminate(s.file);
    }
    private void finish(String token, String kind, String reason) {
        Session s = sessions.get(token); if (s == null) return;
        if ("execution".equals(s.role)) cleanup.run();
        sessions.remove(token);
        if (s.role != null) event("exited", s,
                s.reason.isBlank() || (kind.equals("failed") && !s.explicitReason) ? reason : s.reason,
                s.stopping ? "stopped" : kind);
        if ("scheduler".equals(s.role)) {
            for (Session other : List.copyOf(sessions.values())) requestStop(other, s.instance, "调度宏退出：" + s.name);
        }
    }
    private void event(String type, Session s, String reason, String kind) {
        JsonObject data = new JsonObject(); data.addProperty("event", type); data.addProperty("name", s.name);
        data.addProperty("instance_id", s.instance); data.addProperty("role", s.role); data.addProperty("reason", reason);
        data.addProperty("exit_type", kind); data.addProperty("requested_by", s.requestedBy);
        data.addProperty("requested_reason", s.requestedReason);
        publish(SYSTEM, null, data);
    }
    private void publish(String channel, Session sender, JsonElement data) {
        JsonObject message = new JsonObject(); message.addProperty("sequence", ++sequence);
        message.addProperty("channel", channel); message.addProperty("time", System.currentTimeMillis());
        message.addProperty("sender", sender == null ? "manager" : sender.name);
        message.addProperty("sender_id", sender == null ? "manager" : sender.instance); message.add("data", data.deepCopy());
        for (Session target : sessions.values()) if (target.role != null && (SYSTEM.equals(channel) || target.channels.contains(channel))) {
            if (target.messages.size() == MAX_MESSAGES) { target.messages.removeFirst(); target.dropped++; }
            target.messages.addLast(message.deepCopy());
        }
    }
    private static String text(JsonObject r, String field, int max) {
        if (!r.has(field) || !r.get(field).isJsonPrimitive() || !r.getAsJsonPrimitive(field).isString())
            throw error("INVALID_ARGUMENT", field + " must be a string");
        String v = r.get(field).getAsString();
        if (v.isBlank() || v.length() > max) throw error("INVALID_ARGUMENT", "Invalid " + field); return v;
    }
    private Session session(JsonObject r) {
        Session s = sessions.get(text(r, "macro_token", 100));
        if (s == null) throw error("NOT_REGISTERED", "宏实例不存在或已经退出");
        s.seen = System.nanoTime(); return s;
    }
    /** Returns a result for manager commands, or null to dispatch an authorized game command. */
    public synchronized JsonElement handle(JsonObject r, boolean internal) {
        String command = r.get("command").getAsString();
        if (internal) return null;
        if (command.equals("macro.register")) {
            String role = text(r, "role", 20), name = text(r, "name", 120);
            if (!Set.of("execution", "detection", "scheduler").contains(role)) throw error("INVALID_ARGUMENT", "Unknown macro role");
            Session s = r.has("macro_token") ? session(r) : null;
            if (s != null && (s.role != null || s.stopping || s.closing)) throw error("ALREADY_REGISTERED", "宏已注册或正在停止");
            if (!enabled) throw error("SYSTEM_DISABLED", "宏系统已暂停");
            if (!role.equals("detection") && (sessions.values().stream().anyMatch(x -> role.equals(x.role))
                    || (role.equals("execution") && jsonRunning.getAsBoolean())))
                throw error("MACRO_BUSY", role.equals("execution") ? "已有执行宏在运行，请稍后重试" : "已有调度宏在运行");
            if (s == null) { String token = prepare(null); s = sessions.get(token); }
            if (role.equals("execution")) cleanup.run();
            s.name = name; s.role = role; event("started", s, "", "");
            JsonObject result = info(s); result.addProperty("macro_token", s.token); return result;
        }
        Session s = r.has("macro_token") ? session(r) : null;
        if (s != null && s.role == null && !command.equals("ping") && !command.equals("macro.exit")) {
            // Old SDKs identify managed processes but do not declare a role: default to execution.
            JsonObject registration = new JsonObject(); registration.addProperty("command", "macro.register");
            registration.addProperty("macro_token", s.token); registration.addProperty("name", s.name);
            registration.addProperty("role", "execution"); handle(registration, false);
        }
        if (command.startsWith("bus.") || command.startsWith("system.") || command.startsWith("program.")
                || command.equals("macro.exit") || command.equals("macro.heartbeat")) {
            if (s == null) throw error("NOT_REGISTERED", "请先注册宏");
            switch (command) {
                case "macro.heartbeat" -> { return info(s); }
                case "macro.exit" -> {
                    s.reason = text(r, "reason", 512);
                    s.explicitReason = !r.has("reason_explicit") || r.get("reason_explicit").getAsBoolean();
                    if (s.file == null) finish(s.token, s.stopping ? "stopped" : "normal", s.reason);
                    else {
                        s.closing = true;
                        if ("execution".equals(s.role)) cleanup.run();
                    }
                    return new JsonObject();
                }
                case "bus.subscribe" -> {
                    if (s.channels.size() >= 32) throw error("LIMIT", "Too many subscriptions");
                    s.channels.add(text(r, "channel", 80)); return new JsonObject();
                }
                case "bus.poll" -> {
                    JsonObject result = new JsonObject(); JsonArray messages = new JsonArray();
                    while (!s.messages.isEmpty()) messages.add(s.messages.removeFirst());
                    result.add("messages", messages); result.addProperty("dropped", s.dropped); s.dropped = 0; return result;
                }
                case "bus.publish" -> {
                    if (s.stopping || s.closing) throw error("STOPPING", "宏正在停止");
                    String channel = text(r, "channel", 80);
                    if (channel.startsWith("$")) throw error("FORBIDDEN", "系统通道仅供管理器发布");
                    JsonElement data = r.get("data");
                    if (data == null || data.toString().length() > 4096) throw error("LIMIT", "Message exceeds 4096 characters");
                    publish(channel, s, data); return new JsonObject();
                }
                default -> {
                    if (!"scheduler".equals(s.role) || s.stopping || s.closing) throw error("FORBIDDEN", "只有调度宏可以管理其他宏");
                    switch (command) {
                        case "program.list" -> { JsonArray list = new JsonArray(); sessions.values().forEach(x -> list.add(info(x))); return list; }
                        case "program.start" -> {
                            if (programs == null || !programs.start(text(r, "file", 255))) throw error("START_FAILED", "无法启动宏：文件不存在、正在运行或系统已暂停");
                            return new JsonObject();
                        }
                        case "program.stop" -> {
                            String id = text(r, "instance_id", 100); String reason = text(r, "reason", 512);
                            Session target = sessions.values().stream().filter(x -> x.instance.equals(id)).findFirst()
                                    .orElseThrow(() -> error("NOT_FOUND", "宏实例不存在"));
                            if (target == s || "scheduler".equals(target.role)) throw error("FORBIDDEN", "请用 macro.exit 退出调度宏");
                            requestStop(target, s.instance, reason); return new JsonObject();
                        }
                        case "system.pause" -> {
                            String reason = text(r, "reason", 512); enabled = false;
                            for (Session target : List.copyOf(sessions.values())) if (target != s) requestStop(target, s.instance, reason);
                            cleanup.run(); return new JsonObject();
                        }
                        case "system.resume" -> { enabled = true; return new JsonObject(); }
                        default -> throw error("UNKNOWN_COMMAND", "Unknown manager command");
                    }
                }
            }
        }
        if (s != null && (s.stopping || s.closing) && !Set.of("ping", "status", "macro.status").contains(command))
            throw error("STOPPING", "宏正在停止");
        if (READ.contains(command)) return null;
        if (!enabled || (s != null && (s.stopping || s.closing))) throw error("STOPPING", "宏系统已暂停或此宏正在停止");
        if (s != null && !"execution".equals(s.role)) throw error("FORBIDDEN", "检测宏和调度宏不能控制游戏");
        if (s != null && command.equals("macro.run")) throw error("FORBIDDEN", "执行宏不能另行启动 JSON 宏");
        if (s == null && executing()) throw error("MACRO_BUSY", "已有执行宏在运行，请稍后重试");
        if (s != null && jsonRunning.getAsBoolean()) throw error("MACRO_BUSY", "已有 JSON 执行宏在运行");
        return null;
    }
    private JsonObject info(Session s) {
        JsonObject r = new JsonObject(); r.addProperty("instance_id", s.instance); r.addProperty("name", s.name);
        r.addProperty("file", s.file); r.addProperty("role", s.role == null ? "registering" : s.role);
        r.addProperty("stopping", s.stopping || s.closing); r.addProperty("enabled", enabled); return r;
    }
}
