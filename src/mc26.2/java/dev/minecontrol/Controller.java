package dev.minecontrol;

import com.google.gson.*;
import dev.minecontrol.mixin.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;
import java.util.*;
import java.util.concurrent.*;

public final class Controller {
    private final Minecraft client;
    private final Config config;
    private final Queue<Job> queue = new ConcurrentLinkedQueue<>();
    private final List<Job> waits = new ArrayList<>();
    private final Map<Integer, Long> keys = new ConcurrentHashMap<>();
    private final Map<Integer, Long> buttons = new HashMap<>();
    private final MacroEngine macro = new MacroEngine(this::reset);
    private long tick;
    private Object previousWorld;
    private volatile boolean stopped;
    private record Job(JsonObject request, CompletableFuture<JsonObject> future, long expires) {}
    private static final class CommandError extends RuntimeException {
        final String code;
        CommandError(String code, String message) { super(message); this.code = code; }
    }
    public Controller(Minecraft client, Config config) { this.client = client; this.config = config; previousWorld = client.level; }
    public CompletableFuture<JsonObject> submit(JsonObject request) {
        var future = new CompletableFuture<JsonObject>();
        if (stopped) future.complete(LocalServer.error(request.get("id"), "STOPPED", "Service stopped"));
        else queue.add(new Job(request, future, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.requestTimeoutMs)));
        return future;
    }
    public boolean isHeld(long window, int key) { return client.getWindow().handle() == window && keys.containsKey(key); }
    public void tick() {
        tick++;
        if (previousWorld != null && previousWorld != client.level) { macro.stop(); reset(); }
        previousWorld = client.level;
        for (int key : new ArrayList<>(keys.keySet())) if (tick >= keys.get(key)) keyState(key, false, 0);
        for (int button : new ArrayList<>(buttons.keySet())) if (tick >= buttons.get(button)) buttonState(button, false, 0);
        // Focus changes and screens can reset bindings. Reassert only our held inputs.
        for (int key : keys.keySet()) KeyMapping.set(InputConstants.getKey(new net.minecraft.client.input.KeyEvent(key, 0, 0)), true);
        for (int button : buttons.keySet()) KeyMapping.set(InputConstants.Type.MOUSE.getOrCreate(button), true);
        Job job;
        while ((job = queue.poll()) != null) {
            if (job.future.isDone()) continue;
            if (System.nanoTime() >= job.expires) { job.future.complete(LocalServer.error(job.request.get("id"), "TIMEOUT", "Request expired before execution")); continue; }
            run(job);
        }
        for (Iterator<Job> it = waits.iterator(); it.hasNext();) {
            Job waiting = it.next();
            if (waiting.future.isDone()) { it.remove(); continue; }
            if (screenMatches(string(waiting.request, "screen"))) {
                waiting.future.complete(success(waiting.request, status())); it.remove();
            } else if (System.nanoTime() >= waiting.expires) {
                waiting.future.complete(LocalServer.error(waiting.request.get("id"), "TIMEOUT", "Screen did not appear")); it.remove();
            }
        }
        macro.tick(tick, this::status, this::macroCommand);
    }
    private CompletableFuture<JsonObject> macroCommand(JsonObject request) {
        var future = new CompletableFuture<JsonObject>();
        run(new Job(request, future, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.requestTimeoutMs)));
        return future;
    }
    private void run(Job job) {
        JsonObject request = job.request;
        try {
            String command = string(request, "command");
            JsonElement result;
            switch (command) {
                case "ping" -> { var pong = new JsonObject(); pong.addProperty("pong", true); pong.addProperty("protocol", 1); result = pong; }
                case "status" -> result = status();
                case "macro.run" -> {
                    if (!request.has("script") || !request.get("script").isJsonArray()) throw fail("INVALID_ARGUMENT", "script must be an array");
                    macro.start(request.getAsJsonArray("script")); result = macro.status();
                }
                case "macro.status" -> result = macro.status();
                case "macro.stop" -> { macro.stop(); result = macro.status(); }
                case "input.reset" -> { macro.stop(); reset(); result = status(); }
                case "key.tap", "key.down", "key.up", "key.hold" -> {
                    int key = keyCode(string(request, "key"));
                    boolean down = !command.equals("key.up");
                    int duration = command.equals("key.tap") ? 1 : command.equals("key.hold") ? duration(request) : config.maxHoldTicks;
                    if (down && keys.containsKey(key)) throw fail("INPUT_HELD", "Key is already held; release it first");
                    keyState(key, down, duration); result = status();
                }
                case "mouse.click", "mouse.down", "mouse.up", "mouse.hold" -> {
                    int button = switch (string(request, "button").toLowerCase(Locale.ROOT)) {
                        case "left" -> 0; case "right" -> 1; default -> throw fail("INVALID_ARGUMENT", "button must be left or right");
                    };
                    boolean down = !command.equals("mouse.up");
                    int duration = command.equals("mouse.click") ? 1 : command.equals("mouse.hold") ? duration(request) : config.maxHoldTicks;
                    if (down && buttons.containsKey(button)) throw fail("INPUT_HELD", "Mouse button is already held");
                    buttonState(button, down, duration); result = status();
                }
                case "mouse.goto" -> { gotoGui(number(request, "x"), number(request, "y")); result = status(); }
                case "mouse.move" -> {
                    double dx = number(request, "dx"), dy = number(request, "dy");
                    if (client.gui.screen() != null) gotoGui(guiX() + dx, guiY() + dy);
                    else {
                        if (client.player == null) throw fail("NO_WORLD", "No player loaded");
                        double sensitivity = client.options.sensitivity().get() * 0.6 + 0.2;
                        double scale = sensitivity * sensitivity * sensitivity * 8.0;
                        client.player.turn(dx * scale * (client.options.invertMouseX().get() ? -1 : 1), dy * scale * (client.options.invertMouseY().get() ? -1 : 1));
                    }
                    result = status();
                }
                case "ui.list" -> result = listWidgets();
                case "ui.click", "ui.set", "ui.type" -> { widgetCommand(request, command); result = status(); }
                case "wait.screen" -> {
                    string(request, "screen");
                    int timeout = request.has("timeout_ms") ? integer(request, "timeout_ms") : config.requestTimeoutMs - 100;
                    if (timeout < 1 || timeout >= config.requestTimeoutMs) throw fail("INVALID_ARGUMENT", "timeout_ms must be positive and below requestTimeoutMs");
                    waits.add(new Job(request, job.future, Math.min(job.expires, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout)))); return;
                }
                default -> throw fail("UNKNOWN_COMMAND", "Unknown command: " + command);
            }
            job.future.complete(success(request, result));
        } catch (CommandError e) { job.future.complete(LocalServer.error(request.get("id"), e.code, e.getMessage())); }
        catch (IllegalArgumentException | IllegalStateException e) { job.future.complete(LocalServer.error(request.get("id"), "INVALID_ARGUMENT", e.getMessage())); }
        catch (Exception e) { MineControlClient.LOG.error("Command failed", e); job.future.complete(LocalServer.error(request.get("id"), "INTERNAL_ERROR", "See client log")); }
    }
    private int duration(JsonObject r) {
        int value = integer(r, "ticks");
        if (value < 1 || value > config.maxHoldTicks) throw fail("INVALID_ARGUMENT", "ticks must be 1.." + config.maxHoldTicks);
        return value;
    }
    private int mods() {
        int mods = 0;
        if (held(GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT)) mods |= GLFW.GLFW_MOD_SHIFT;
        if (held(GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL)) mods |= GLFW.GLFW_MOD_CONTROL;
        if (held(GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT)) mods |= GLFW.GLFW_MOD_ALT;
        if (held(GLFW.GLFW_KEY_LEFT_SUPER, GLFW.GLFW_KEY_RIGHT_SUPER)) mods |= GLFW.GLFW_MOD_SUPER;
        return mods;
    }
    private boolean held(int a, int b) { return InputConstants.isKeyDown(client.getWindow(), a) || InputConstants.isKeyDown(client.getWindow(), b); }
    private void keyState(int key, boolean down, int ticks) {
        if (down) keys.put(key, tick + ticks); else if (keys.remove(key) == null) return;
        ((KeyboardInvoker) client.keyboardHandler).minecontrol$key(client.getWindow().handle(), down ? GLFW.GLFW_PRESS : GLFW.GLFW_RELEASE, new net.minecraft.client.input.KeyEvent(key, 0, mods()));
    }
    private void buttonState(int button, boolean down, int ticks) {
        if (down) buttons.put(button, tick + ticks); else if (buttons.remove(button) == null) return;
        ((MouseInvoker) client.mouseHandler).minecontrol$button(client.getWindow().handle(), new net.minecraft.client.input.MouseButtonInfo(button, mods()), down ? GLFW.GLFW_PRESS : GLFW.GLFW_RELEASE);
    }
    private void reset() {
        for (int key : new ArrayList<>(keys.keySet())) keyState(key, false, 0);
        for (int button : new ArrayList<>(buttons.keySet())) buttonState(button, false, 0);
    }
    public void stop() {
        stopped = true; macro.stop(); reset();
        for (Job job : queue) job.future.complete(LocalServer.error(job.request.get("id"), "STOPPED", "Service stopped"));
        for (Job job : waits) job.future.complete(LocalServer.error(job.request.get("id"), "STOPPED", "Service stopped"));
        queue.clear(); waits.clear();
    }
    private double guiX() { return client.mouseHandler.xpos() * client.getWindow().getGuiScaledWidth() / client.getWindow().getScreenWidth(); }
    private double guiY() { return client.mouseHandler.ypos() * client.getWindow().getGuiScaledHeight() / client.getWindow().getScreenHeight(); }
    private void gotoGui(double x, double y) {
        if (client.gui.screen() == null) throw fail("NO_SCREEN", "GUI coordinates require an open screen");
        var window = client.getWindow();
        if (x < 0 || y < 0 || x >= window.getGuiScaledWidth() || y >= window.getGuiScaledHeight()) throw fail("INVALID_ARGUMENT", "Coordinates outside GUI");
        ((MouseInvoker) client.mouseHandler).minecontrol$cursor(window.handle(), x * window.getScreenWidth() / window.getGuiScaledWidth(), y * window.getScreenHeight() / window.getGuiScaledHeight());
    }
    private List<AbstractWidget> widgets() {
        if (client.gui.screen() == null) throw fail("NO_SCREEN", "No screen open");
        List<AbstractWidget> widgets = new ArrayList<>();
        collect(client.gui.screen(), widgets, Collections.newSetFromMap(new IdentityHashMap<>()));
        return widgets;
    }
    private void collect(ContainerEventHandler parent, List<AbstractWidget> result, Set<GuiEventListener> seen) {
        for (GuiEventListener element : parent.children()) {
            if (!seen.add(element)) continue;
            if (element instanceof AbstractWidget widget) result.add(widget);
            if (element instanceof ContainerEventHandler nested) collect(nested, result, seen);
        }
    }
    private JsonArray listWidgets() {
        var array = new JsonArray(); var widgets = widgets();
        for (int i = 0; i < widgets.size(); i++) {
            var w = widgets.get(i); var item = new JsonObject();
            item.addProperty("id", i); item.addProperty("type", w.getClass().getSimpleName()); item.addProperty("label", w.getMessage().getString());
            item.addProperty("enabled", w.active); item.addProperty("visible", w.visible);
            item.addProperty("x", w.getX()); item.addProperty("y", w.getY()); item.addProperty("width", w.getWidth()); item.addProperty("height", w.getHeight());
            if (w instanceof EditBox text) { item.addProperty("editable", ((TextFieldAccessor) text).minecontrol$editable()); item.addProperty("text", text.getValue()); }
            Boolean value = booleanValue(w);
            if (value != null) item.addProperty("value", value);
            array.add(item);
        }
        return array;
    }
    private void widgetCommand(JsonObject request, String command) {
        var widgets = widgets(); int id = integer(request, "widget");
        if (id < 0 || id >= widgets.size()) throw fail("STALE_WIDGET", "Widget ID not in current screen; run ui list");
        AbstractWidget widget = widgets.get(id);
        if (!widget.active || !widget.visible) throw fail("DISABLED_WIDGET", "Widget is disabled or hidden");
        if (command.equals("ui.type")) {
            if (!(widget instanceof EditBox text)) throw fail("UNSUPPORTED_WIDGET", "Widget is not a text field");
            if (!((TextFieldAccessor) text).minecontrol$editable()) throw fail("READ_ONLY", "Text field is read only");
            String value = string(request, "text");
            String mode = request.has("mode") ? string(request, "mode") : "replace";
            if (mode.equals("replace")) text.setValue(value);
            else if (mode.equals("append")) { text.setValue(text.getValue() + value); }
            else throw fail("INVALID_ARGUMENT", "mode must be replace or append");
            client.gui.screen().setFocused(text); text.setFocused(true); return;
        }
        if (command.equals("ui.set")) {
            Boolean current = booleanValue(widget);
            if (current == null) throw fail("UNSUPPORTED_WIDGET", "ui set requires a checkbox or a boolean cycling button");
            if (!request.has("value") || !request.get("value").isJsonPrimitive() || !request.get("value").getAsJsonPrimitive().isBoolean()) throw fail("INVALID_ARGUMENT", "value must be boolean");
            if (current == request.get("value").getAsBoolean()) return;
        }
        double x = widget.getX() + widget.getWidth() / 2.0, y = widget.getY() + widget.getHeight() / 2.0;
        var screen = client.gui.screen();
        if (!screen.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(x, y, new net.minecraft.client.input.MouseButtonInfo(0, mods())), false)) throw fail("UNSUPPORTED_WIDGET", "Widget did not accept click; use mouse coordinates");
        screen.mouseReleased(new net.minecraft.client.input.MouseButtonEvent(x, y, new net.minecraft.client.input.MouseButtonInfo(0, mods())));
        if (command.equals("ui.set") && !Objects.equals(booleanValue(widget), request.get("value").getAsBoolean()))
            throw fail("UNSUPPORTED_WIDGET", "Widget did not reach requested boolean value");
    }
    private Boolean booleanValue(AbstractWidget widget) {
        if (widget instanceof Checkbox checkbox) return checkbox.selected();
        if (widget instanceof CycleButton<?> cycling && cycling.getValue() instanceof Boolean value) return value;
        return null;
    }
    private boolean screenMatches(String name) {
        if (name.equalsIgnoreCase("none")) return client.gui.screen() == null;
        return client.gui.screen() != null && (client.gui.screen().getClass().getName().equals(name) || client.gui.screen().getClass().getSimpleName().equals(name));
    }
    private JsonObject status() {
        var result = new JsonObject();
        if (client.gui.screen() == null) result.add("screen", JsonNull.INSTANCE); else result.addProperty("screen", client.gui.screen().getClass().getName());
        result.addProperty("world_loaded", client.level != null);
        result.addProperty("window_focused", GLFW.glfwGetWindowAttrib(client.getWindow().handle(), GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE);
        result.addProperty("background_control", config.backgroundControl);
        result.addProperty("cursor_locked", client.mouseHandler.isMouseGrabbed()); result.addProperty("tick", tick);
        result.addProperty("gui_width", client.getWindow().getGuiScaledWidth()); result.addProperty("gui_height", client.getWindow().getGuiScaledHeight());
        result.addProperty("cursor_x", guiX()); result.addProperty("cursor_y", guiY());
        result.add("held_keys", new Gson().toJsonTree(new TreeSet<>(keys.keySet()))); result.add("held_buttons", new Gson().toJsonTree(new TreeSet<>(buttons.keySet())));
        if (client.player != null) { result.addProperty("yaw", client.player.getYRot()); result.addProperty("pitch", client.player.getXRot());
            result.addProperty("player_x", client.player.getX()); result.addProperty("player_y", client.player.getY()); result.addProperty("player_z", client.player.getZ());
            result.addProperty("on_ground", client.player.onGround()); }
        return result;
    }
    private static JsonObject success(JsonObject request, JsonElement result) {
        var response = new JsonObject(); response.add("id", request.get("id")); response.addProperty("ok", true); response.add("result", result); return response;
    }
    private static CommandError fail(String code, String message) { return new CommandError(code, message); }
    private static String string(JsonObject r, String name) {
        if (!r.has(name) || !r.get(name).isJsonPrimitive() || !r.get(name).getAsJsonPrimitive().isString()) throw fail("INVALID_ARGUMENT", name + " must be a string");
        return r.get(name).getAsString();
    }
    private static double number(JsonObject r, String name) {
        if (!r.has(name) || !r.get(name).isJsonPrimitive() || !r.get(name).getAsJsonPrimitive().isNumber()) throw fail("INVALID_ARGUMENT", name + " must be a number");
        double value = r.get(name).getAsDouble();
        if (!Double.isFinite(value) || Math.abs(value) > 1000000) throw fail("INVALID_ARGUMENT", name + " is outside supported range");
        return value;
    }
    private static int integer(JsonObject r, String name) {
        double value = number(r, name);
        if (value != Math.rint(value)) throw fail("INVALID_ARGUMENT", name + " must be an integer");
        return (int) value;
    }
    private static int keyCode(String name) {
        String normalized = name.toUpperCase(Locale.ROOT).replace('-', '_');
        normalized = switch (normalized) { case "CTRL", "CONTROL" -> "LEFT_CONTROL"; case "SHIFT" -> "LEFT_SHIFT"; case "ALT" -> "LEFT_ALT"; case "ESC" -> "ESCAPE"; case "RETURN" -> "ENTER"; case "CMD", "SUPER" -> "LEFT_SUPER"; default -> normalized; };
        try {
            int code = GLFW.class.getField("GLFW_KEY_" + normalized).getInt(null);
            if (code < GLFW.GLFW_KEY_SPACE || code > GLFW.GLFW_KEY_LAST) throw new ReflectiveOperationException();
            return code;
        } catch (ReflectiveOperationException e) { throw fail("UNKNOWN_KEY", "Unknown GLFW key: " + name); }
    }
}
