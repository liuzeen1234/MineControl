package dev.minecontrol;

import com.google.gson.*;
import net.minecraft.client.option.KeyBinding;
import dev.minecontrol.mixin.KeyboardInvoker;
import dev.minecontrol.mixin.MouseInvoker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.*;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Runs only in a test client after config/input-capture-test.trigger is created in a loaded world. */
public final class InputCaptureClientTest implements ClientModInitializer {
    private final Path config = FabricLoader.getInstance().getConfigDir();
    private final List<String> passed = new ArrayList<>();
    private CompletableFuture<?> pending;
    private int stage, waitingTicks;
    private long started;
    private Controller controller;
    private KeyBinding stopKey;
    private String originalStop;
    private float originalYaw;
    private ProbeScreen probe;
    private final String program = "InputCaptureProbe.java";

    @Override public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try { tick(client); }
            catch (Throwable e) {
                MineControlClient.LOG.error("Input capture regression failed", e);
                MineControlClient.stopRunningMacros();
                restoreStopKey();
                writeReport("failed", e.toString());
                stage = -1;
            }
        });
    }
    private void tick(MinecraftClient client) throws Exception {
        if (stage < 0) return;
        if (stage == 0) {
            if (client.world == null || !Files.exists(config.resolve("input-capture-test.trigger"))) return;
            Files.delete(config.resolve("input-capture-test.trigger"));
            MineControlClient.javaMacros().resume();
            started = System.nanoTime();
            controller = (Controller) field(MineControlClient.class, "controller").get(null);
            stopKey = (KeyBinding) field(MineControlClient.class, "stopMacro").get(null);
            originalStop = stopKey.getBoundKeyTranslationKey();
            Path source = config.resolve("InputCaptureProbe.java");
            Files.writeString(source, "class InputCaptureProbe { public static void main(String[] args) throws Exception { try (var mc = dev.minecontrol.sdk.MineControl.connect(Integer.parseInt(args[0]), dev.minecontrol.sdk.MineControl.MacroRole.EXECUTION, \"InputCaptureProbe\")) { while (!mc.stopRequested()) Thread.sleep(50); } } }");
            pending = MineControlClient.javaMacros().addFiles(List.of(source));
            stage = 1; return;
        }
        if (System.nanoTime() - started > TimeUnit.SECONDS.toNanos(30)) throw new AssertionError("Probe timed out at stage " + stage);
        if (pending != null) {
            if (!pending.isDone()) return;
            pending.get(); pending = null;
        }
        switch (stage) {
            case 1 -> {
                client.setScreen(null);
                client.options.forwardKey.setPressed(true);
                rawButton(client, GLFW.GLFW_PRESS);
                check(MineControlClient.javaMacros().start(program), "Java macro started");
                stage = 2;
            }
            case 2 -> {
                if (!MineControlClient.inputCaptured()) return;
                MineControlClient.updateInputCapture();
                check(MineControlClient.inputCaptured(), "Java macro captures input");
                check(GLFW.glfwGetInputMode(client.getWindow().getHandle(), GLFW.GLFW_CURSOR) == GLFW.GLFW_CURSOR_NORMAL,
                        "OS cursor is visible and released while Java macro runs");
                check(client.mouse.isCursorLocked(), "logical grab retained for macro actions");
                GLFW.glfwSetInputMode(client.getWindow().getHandle(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
                client.mouse.lockCursor();
                check(GLFW.glfwGetInputMode(client.getWindow().getHandle(), GLFW.GLFW_CURSOR) == GLFW.GLFW_CURSOR_NORMAL,
                        "vanilla regrab cannot hide cursor during macro");
                check(!client.options.forwardKey.isPressed() && !client.mouse.wasLeftButtonClicked(), "pre-held physical inputs cleared");
                originalYaw = client.player.getYaw();
                rawKey(client, GLFW.GLFW_KEY_W, GLFW.GLFW_PRESS);
                rawKey(client, GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_PRESS);
                rawKey(client, GLFW.GLFW_KEY_T, GLFW.GLFW_PRESS);
                check(!client.options.forwardKey.isPressed() && client.currentScreen == null, "physical movement and game shortcuts blocked");
                rawKey(client, GLFW.GLFW_KEY_F8, GLFW.GLFW_PRESS);
                check(client.currentScreen instanceof MacroScreen && !MineControlClient.capturePhysicalInput(client.getWindow().getHandle()),
                        "physical F8 opens an operable macro console during execution");
                client.setScreen(null);
                rawButton(client, GLFW.GLFW_PRESS);
                check(!client.mouse.wasLeftButtonClicked(), "physical mouse click blocked");
                double x = client.mouse.getX(), y = client.mouse.getY();
                ((MouseInvoker) client.mouse).minecontrol$cursor(client.getWindow().getHandle(), x + 200, y + 100);
                client.mouse.updateMouse();
                check(client.mouse.getX() == x && client.player.getYaw() == originalYaw, "physical mouse motion blocked");
                int slot = client.player.getInventory().selectedSlot;
                invoke(client.mouse, "onMouseScroll", new Class<?>[]{long.class,double.class,double.class}, client.getWindow().getHandle(), 0d, 1d);
                check(client.player.getInventory().selectedSlot == slot, "physical scroll blocked");
                pending = CompletableFuture.allOf(command("key.down", "key", "w"), command("mouse.down", "button", "left"),
                        command("mouse.move", "dx", 20, "dy", 0), command("key.tap", "key", "f2"));
                stage = 3;
            }
            case 3 -> {
                check(client.options.forwardKey.isPressed() && client.mouse.wasLeftButtonClicked(), "simulated keyboard and mouse still work");
                check(client.player.getYaw() != originalYaw, "simulated look still works");
                rawKey(client, GLFW.GLFW_KEY_W, GLFW.GLFW_RELEASE);
                rawButton(client, GLFW.GLFW_RELEASE);
                check(client.options.forwardKey.isPressed() && client.mouse.wasLeftButtonClicked(), "physical releases cannot cancel macro holds");
                check(InputUtil.isKeyPressed(client.getWindow().getHandle(), GLFW.GLFW_KEY_W), "polled keyboard state preserves simulated holds");
                probe = new ProbeScreen(); client.setScreen(probe);
                invoke(client.keyboard, "onChar", new Class<?>[]{long.class,int.class,int.class}, client.getWindow().getHandle(), (int) 'x', 0);
                check(probe.text.getText().equals("safe"), "physical text entry blocked");
                pending = CompletableFuture.allOf(command("key.up", "key", "w"), command("mouse.up", "button", "left"),
                        command("ui.type", "widget", 0, "text", "macro"), command("key.tap", "key", "f9"));
                stage = 4;
            }
            case 4 -> {
                check(probe.text.getText().equals("macro"), "simulated UI typing still works");
                check(MineControlClient.inputCaptured(), "simulated stop key does not masquerade as physical stop");
                rawKey(client, GLFW.GLFW_KEY_F9, GLFW.GLFW_PRESS);
                waitingTicks = 0; stage = 5;
            }
            case 5 -> {
                if (MineControlClient.javaMacros().active()) { waitingTicks++; return; }
                check(!MineControlClient.inputCaptured(), "physical F9 stops Java macro");
                check(!client.mouse.wasLeftButtonClicked() && !client.options.forwardKey.isPressed(), "stop releases simulated inputs");
                client.setScreen(null);
                rawKey(client, GLFW.GLFW_KEY_W, GLFW.GLFW_PRESS);
                check(client.options.forwardKey.isPressed(), "manual input restored after stop");
                rawKey(client, GLFW.GLFW_KEY_W, GLFW.GLFW_RELEASE);
                stopKey.setBoundKey(InputUtil.Type.KEYSYM.createFromCode(GLFW.GLFW_KEY_F10)); KeyBinding.updateKeysByCode();
                MineControlClient.javaMacros().resume();
                pending = command("macro.run", "script", JsonParser.parseString("[{\"wait\":10000}]"));
                stage = 6;
            }
            case 6 -> {
                check(MineControlClient.inputCaptured() && controller.macroRunning(), "JSON macro captures input");
                check(GLFW.glfwGetInputMode(client.getWindow().getHandle(), GLFW.GLFW_CURSOR) == GLFW.GLFW_CURSOR_NORMAL,
                        "JSON macro releases OS cursor");
                check(MineControlClient.stopKeyLabel().contains("F10"), "HUD follows remapped stop key");
                rawKey(client, GLFW.GLFW_KEY_F9, GLFW.GLFW_PRESS);
                check(controller.macroRunning(), "old stop key no longer triggers after remapping");
                MineControlClient.pollStopShortcut(key -> key == GLFW.GLFW_KEY_F10, button -> false);
                check(!controller.macroRunning(), "raw stop polling works independently of keyboard callbacks");
                MineControlClient.javaMacros().resume();
                pending = command("macro.run", "script", JsonParser.parseString("[{\"wait\":10000}]"));
                stage = 7;
            }
            case 7 -> {
                rawKey(client, GLFW.GLFW_KEY_F10, GLFW.GLFW_PRESS);
                check(!controller.macroRunning(), "remapped physical stop key stops JSON macro");
                stopKey.setBoundKey(InputUtil.Type.KEYSYM.createFromCode(GLFW.GLFW_KEY_UNKNOWN)); KeyBinding.updateKeysByCode();
                MineControlClient.javaMacros().resume();
                pending = command("macro.run", "script", JsonParser.parseString("[{\"wait\":10000}]"));
                stage = 8;
            }
            case 8 -> {
                check(MineControlClient.stopKeyLabel().equals("F9"), "unbound stop key advertises F9 fallback");
                MineControlClient.pollStopShortcut(key -> key == GLFW.GLFW_KEY_F9, button -> false);
                check(!controller.macroRunning(), "unbound stop key still polls physical F9");
                stage = 9;
            }
            case 9 -> {
                check(!MineControlClient.inputCaptured(), "capture fully released after stop");
                int expected = GLFW.glfwGetWindowAttrib(client.getWindow().getHandle(), GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE
                        ? GLFW.GLFW_CURSOR_DISABLED : GLFW.GLFW_CURSOR_NORMAL;
                check(GLFW.glfwGetInputMode(client.getWindow().getHandle(), GLFW.GLFW_CURSOR) == expected,
                        "cursor capture restored only when game has focus");
                restoreStopKey();
                writeReport("passed", "");
                MineControlClient.LOG.info("Input capture in-client checks passed: {}", passed);
                stage = -1;
            }
        }
    }
    private CompletableFuture<JsonObject> command(String command, Object... values) {
        var request = new JsonObject(); request.addProperty("id", "input-capture-test"); request.addProperty("command", command);
        request.addProperty("_internal_macro", true); // Trusted in-client probe emulates executor-origin input.
        var gson = new Gson();
        for (int i=0;i<values.length;i+=2) request.add((String) values[i], gson.toJsonTree(values[i+1]));
        return controller.submit(request).thenApply(response -> {
            if (!response.get("ok").getAsBoolean()) throw new AssertionError(response);
            return response;
        });
    }
    private void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        passed.add(label);
    }
    private void restoreStopKey() {
        if (stopKey != null && originalStop != null) { stopKey.setBoundKey(InputUtil.fromTranslationKey(originalStop)); KeyBinding.updateKeysByCode(); }
    }
    private void writeReport(String state, String error) {
        var result = new JsonObject(); result.addProperty("state", state); result.addProperty("error", error);
        result.add("checks", new Gson().toJsonTree(passed));
        try { Files.writeString(config.resolve("input-capture-test-report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(result)); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private static Field field(Class<?> owner, String name) throws Exception {
        Field result = owner.getDeclaredField(name); result.setAccessible(true); return result;
    }
    private static void invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true); method.invoke(target, args);
    }
    private static void rawKey(MinecraftClient client, int key, int action) {
        ((KeyboardInvoker) client.keyboard).minecontrol$key(client.getWindow().getHandle(), key, 0, action, 0);
    }
    private static void rawButton(MinecraftClient client, int action) {
        ((MouseInvoker) client.mouse).minecontrol$button(client.getWindow().getHandle(), 0, action, 0);
    }
    private static final class ProbeScreen extends Screen {
        TextFieldWidget text;
        ProbeScreen() { super(Text.literal("Input capture regression")); }
        @Override protected void init() {
            text = addDrawableChild(new TextFieldWidget(textRenderer, 20, 80, 160, 20, Text.literal("Probe text")));
            text.setText("safe"); setInitialFocus(text);
        }
        @Override public boolean shouldPause() { return false; }
    }
}
