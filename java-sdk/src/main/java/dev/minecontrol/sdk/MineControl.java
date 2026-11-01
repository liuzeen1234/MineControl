package dev.minecontrol.sdk;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import static dev.minecontrol.sdk.Snapshots.*;

/** Blocking Java API for an external program. Supports execution, detection and scheduler macro sessions. */
public final class MineControl implements AutoCloseable {
    private static final Gson GSON = new Gson();
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private final int port, timeoutMs;
    private final Thread shutdownHook;
    private boolean closed, inputTouched, registered, explicitExitReason;
    private String macroToken = System.getenv("MINECONTROL_MACRO_TOKEN");
    private String exitReason = "正常结束";
    private java.util.concurrent.ScheduledExecutorService heartbeat;
    public enum MacroRole { EXECUTION, DETECTION, SCHEDULER }
    public record MacroInstance(String instance_id, String name, String file, String role, boolean stopping, boolean enabled) {}
    public record Broadcast(long sequence, String channel, long time, String sender, String sender_id, JsonElement data) {}
    public record Mailbox(List<Broadcast> messages, long dropped) {}
    private final Keys keys = new Keys();
    private final Mouse mouse = new Mouse();
    private final Ui ui = new Ui();

    public enum MouseButton { LEFT, RIGHT }
    public enum TextMode { REPLACE, APPEND }

    private MineControl(int port, int timeoutMs) {
        if (port < 1 || port > 65535 || timeoutMs < 1) throw new IllegalArgumentException("Invalid port or timeout");
        this.port = port;
        this.timeoutMs = timeoutMs;
        shutdownHook = new Thread(() -> {
            try { close(); } catch (IOException | RuntimeException ignored) { /* Mod's input expiry remains the fallback. */ }
        }, "MineControl SDK cleanup");
    }
    public static MineControl connect() throws IOException { return connect(25580); }
    public static MineControl connect(int port) throws IOException { return connect(port, Duration.ofSeconds(20)); }
    public static MineControl connect(int port, Duration timeout) throws IOException {
        long millis = timeout.toMillis();
        if (millis < 1 || millis > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid timeout");
        var client = new MineControl(port, (int) millis);
        JsonObject pong = client.call("ping", new JsonObject()).getAsJsonObject();
        if (!pong.has("protocol") || pong.get("protocol").getAsInt() != 1)
            throw new IOException("Unsupported MineControl protocol");
        Runtime.getRuntime().addShutdownHook(client.shutdownHook);
        return client;
    }
    /** Declare the role before reading game state. A busy execution slot raises MACRO_BUSY; retry explicitly. */
    public static MineControl connect(int port, MacroRole role, String name) throws IOException {
        MineControl mc = connect(port);
        try { mc.register(role, name); return mc; }
        catch (IOException | RuntimeException e) { mc.closed = true; Runtime.getRuntime().removeShutdownHook(mc.shutdownHook); throw e; }
    }
    public synchronized MacroInstance register(MacroRole role, String name) throws IOException {
        if (registered) throw new IllegalStateException("Macro already registered");
        JsonObject result = call("macro.register", params("role", role.name().toLowerCase(Locale.ROOT), "name", name)).getAsJsonObject();
        macroToken = result.get("macro_token").getAsString(); registered = true;
        heartbeat = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
            Thread t = new Thread(task, "MineControl macro heartbeat"); t.setDaemon(true); return t;
        });
        heartbeat.scheduleWithFixedDelay(() -> {
            try { call("macro.heartbeat", new JsonObject()); } catch (IOException ignored) { /* Next user call reports loss of session. */ }
        }, 5, 5, java.util.concurrent.TimeUnit.SECONDS);
        return decode(result, MacroInstance.class);
    }
    public void subscribe(String channel) throws IOException { call("bus.subscribe", params("channel", channel)); }
    public void broadcast(String channel, JsonElement data) throws IOException { call("bus.publish", params("channel", channel, "data", data)); }
    public Mailbox pollBroadcasts() throws IOException { return decode(call("bus.poll", new JsonObject()), Mailbox.class); }
    public List<MacroInstance> macros() throws IOException { return List.of(decode(call("program.list", new JsonObject()), MacroInstance[].class)); }
    public void startMacro(String file) throws IOException { call("program.start", params("file", file)); }
    public void stopMacro(String instanceId, String reason) throws IOException { call("program.stop", params("instance_id", instanceId, "reason", reason)); }
    public void pauseMacros(String reason) throws IOException { call("system.pause", params("reason", reason)); }
    public void resumeMacros() throws IOException { call("system.resume", new JsonObject()); }
    public synchronized void exitReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 512) throw new IllegalArgumentException("Invalid exit reason");
        exitReason = reason; explicitExitReason = true;
    }
    /** Stop requests are also delivered on $system; this polls without consuming broadcasts. */
    public boolean stopRequested() throws IOException {
        return call("macro.heartbeat", new JsonObject()).getAsJsonObject().get("stopping").getAsBoolean();
    }
    public Keys key() { return keys; }
    public Mouse mouse() { return mouse; }
    public Ui ui() { return ui; }
    public Status status() throws IOException { return read("status", Status.class); }
    public Position position() throws IOException { return read("position", Position.class); }
    public Observation observe() throws IOException { return read("observe", Observation.class); }
    /** Loaded, living non-spectator players within a spherical radius; excludes yourself. Minecraft 26.2. */
    public List<NearbyPlayer> nearbyPlayers(double radius) throws IOException {
        if (!Double.isFinite(radius) || radius <= 0 || radius > 128) throw new IllegalArgumentException("Invalid radius");
        return List.of(decode(call("players.nearby", params("radius", radius)), NearbyPlayer[].class));
    }
    /** Sends once; a successful reply means sent, not that the server accepted the command. */
    public void sendCommand(String command) throws IOException {
        call("chat.command", params("text", command));
    }
    public Inventory inventory() throws IOException { return read("inventory", Inventory.class); }
    public Region region(BlockPos from, BlockPos to) throws IOException {
        return decode(call("region.query", params("from", new int[]{from.x(), from.y(), from.z()},
                "to", new int[]{to.x(), to.y(), to.z()})), Region.class);
    }
    public synchronized Status resetInputs() throws IOException {
        Status result = read("input.reset", Status.class);
        inputTouched = false;
        return result;
    }

    /** Wait relative to the first status snapshot. Uses game ticks, with a wall-clock deadline. */
    public void waitTicks(long ticks) throws IOException, InterruptedException {
        waitTicks(ticks, Duration.ofMinutes(5));
    }
    public void waitTicks(long ticks, Duration timeout) throws IOException, InterruptedException {
        if (ticks < 0 || timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Invalid wait");
        if (Thread.interrupted()) throw new InterruptedException();
        if (ticks == 0) return;
        long started = System.nanoTime(), budget = timeout.toNanos();
        long target = Math.addExact(status().tick(), ticks);
        while (true) {
            if (System.nanoTime() - started >= budget) throw new SocketTimeoutException("Client tick wait timed out");
            Thread.sleep(10);
            if (status().tick() >= target) return;
        }
    }
    public Status waitScreen(String screen, int timeoutMs) throws IOException {
        return decode(call("wait.screen", params("screen", screen, "timeout_ms", timeoutMs)), Status.class);
    }

    public final class Keys {
        private Keys() {}
        public Status tap(String key) throws IOException { return input("key.tap", params("key", key)); }
        public Status down(String key) throws IOException { return input("key.down", params("key", key)); }
        public Status up(String key) throws IOException { return input("key.up", params("key", key)); }
        public Status hold(String key, int ticks) throws IOException {
            return input("key.hold", params("key", key, "ticks", ticks));
        }
        public void holdAndWait(String key, int ticks) throws IOException, InterruptedException {
            long target = Math.addExact(hold(key, ticks).tick(), ticks);
            waitUntilTick(target);
        }
    }
    public final class Mouse {
        private Mouse() {}
        private Status button(String command, MouseButton button) throws IOException {
            return input(command, params("button", button.name().toLowerCase(Locale.ROOT)));
        }
        public Status click(MouseButton button) throws IOException { return button("mouse.click", button); }
        public Status down(MouseButton button) throws IOException { return button("mouse.down", button); }
        public Status up(MouseButton button) throws IOException { return button("mouse.up", button); }
        public Status hold(MouseButton button, int ticks) throws IOException {
            return input("mouse.hold", params("button", button.name().toLowerCase(Locale.ROOT), "ticks", ticks));
        }
        public void holdAndWait(MouseButton button, int ticks) throws IOException, InterruptedException {
            long target = Math.addExact(hold(button, ticks).tick(), ticks);
            waitUntilTick(target);
        }
        public Status move(double dx, double dy) throws IOException {
            return input("mouse.move", params("dx", dx, "dy", dy));
        }
        public Status goTo(double x, double y) throws IOException {
            return input("mouse.goto", params("x", x, "y", y));
        }
    }
    public final class Ui {
        private Ui() {}
        public List<Widget> list() throws IOException {
            return List.of(decode(call("ui.list", new JsonObject()), Widget[].class));
        }
        public Status click(int widget) throws IOException { return input("ui.click", params("widget", widget)); }
        public Status set(int widget, boolean value) throws IOException {
            return input("ui.set", params("widget", widget, "value", value));
        }
        public Status type(int widget, String text) throws IOException { return type(widget, text, TextMode.REPLACE); }
        public Status type(int widget, String text, TextMode mode) throws IOException {
            return input("ui.type", params("widget", widget, "text", text, "mode", mode.name().toLowerCase(Locale.ROOT)));
        }
    }
    private void waitUntilTick(long target) throws IOException, InterruptedException {
        long start = System.nanoTime();
        while (status().tick() < target) {
            if (System.nanoTime() - start > Duration.ofMinutes(5).toNanos())
                throw new SocketTimeoutException("Held input wait timed out");
            Thread.sleep(10);
        }
    }
    private synchronized Status input(String command, JsonObject arguments) throws IOException {
        // Mark before sending: a lost reply does not mean the action did not execute.
        inputTouched = true;
        return decode(call(command, arguments), Status.class);
    }
    private <T> T read(String command, Class<T> type) throws IOException {
        return decode(call(command, new JsonObject()), type);
    }
    private static <T> T decode(JsonElement value, Class<T> type) throws IOException {
        try { return GSON.fromJson(value, type); }
        catch (RuntimeException e) { throw new IOException("Invalid " + type.getSimpleName() + " response", e); }
    }
    private static JsonObject params(Object... pairs) {
        var result = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) result.add((String) pairs[i], GSON.toJsonTree(pairs[i + 1]));
        return result;
    }
    /** Waits through world loading only when the Mod explicitly rejected execution.
     * Transport failures and other errors are never retried (the action may have happened).
     */
    public synchronized JsonElement call(String command, JsonObject arguments) throws IOException {
        while (true) {
            try { return callOnce(command, arguments); }
            catch (CommandException e) {
                if (!e.code().equals("WORLD_LOADING")) throw e;
                try { wait(100); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Interrupted while waiting for world loading");
                }
            }
        }
    }
    private JsonElement callOnce(String command, JsonObject arguments) throws IOException {
        if (closed) throw new IOException("MineControl client is closed");
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Interrupted before request");
        if (command.startsWith("key.") || command.startsWith("mouse.") || command.startsWith("ui.")
                || command.equals("macro.run")) inputTouched = true;
        String id = UUID.randomUUID().toString();
        JsonObject request = arguments.deepCopy();
        request.addProperty("id", id);
        request.addProperty("command", command);
        if (macroToken != null && !macroToken.isBlank()) request.addProperty("macro_token", macroToken);
        byte[] bytes = (request + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length - 1 > 65536) throw new IOException("Request exceeds default 65536-byte limit");
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            socket.setTcpNoDelay(true);
            socket.getOutputStream().write(bytes);
            socket.getOutputStream().flush();
            JsonObject response;
            try { response = JsonParser.parseString(readLine(new BufferedInputStream(socket.getInputStream()))).getAsJsonObject(); }
            catch (RuntimeException e) { throw new IOException("Invalid JSON response", e); }
            if (!response.has("id") || !new JsonPrimitive(id).equals(response.get("id"))
                    || !response.has("ok") || !response.get("ok").isJsonPrimitive()
                    || !response.getAsJsonPrimitive("ok").isBoolean()) throw new IOException("Invalid response envelope or id");
            if (!response.get("ok").getAsBoolean()) {
                try {
                    JsonObject error = response.getAsJsonObject("error");
                    throw new CommandException(error.get("code").getAsString(), error.get("message").getAsString());
                } catch (CommandException e) { throw e; }
                catch (RuntimeException e) { throw new IOException("Invalid error response", e); }
            }
            if (!response.has("result")) throw new IOException("Missing response result");
            return response.get("result");
        }
    }
    private static String readLine(InputStream input) throws IOException {
        var buffer = new ByteArrayOutputStream();
        int c;
        while ((c = input.read()) != -1) {
            if (c == '\n') return buffer.toString(StandardCharsets.UTF_8);
            if (buffer.size() >= MAX_RESPONSE_BYTES) throw new IOException("Response exceeds byte limit");
            buffer.write(c);
        }
        throw new EOFException("Incomplete response");
    }
    /** Reports session exit; legacy unregistered clients reset only if they sent input operations. */
    @Override public synchronized void close() throws IOException {
        if (closed) return;
        boolean interrupted = Thread.interrupted();
        try {
            if (registered || (macroToken != null && !macroToken.isBlank())) call("macro.exit", params("reason", exitReason, "reason_explicit", explicitExitReason));
            else if (inputTouched) resetInputs();
        } finally {
            closed = true;
            if (heartbeat != null) heartbeat.shutdownNow();
            try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
            catch (IllegalStateException ignored) { /* JVM is already shutting down. */ }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
