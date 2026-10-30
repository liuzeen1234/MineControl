package dev.minecontrol;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import org.lwjgl.glfw.GLFW;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.resources.Identifier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.function.IntPredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
public final class MineControlClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("minecontrol");
    private static Controller controller;
    private static LocalServer server;
    private static boolean background;
    private static boolean oldPause;
    private static JavaMacroManager javaMacros;
    private static Object previousLevel;
    private static KeyMapping stopMacro;
    private static boolean inputWasCaptured;
    private static boolean restoreCursorWhenFocused;
    public static JavaMacroManager javaMacros() { return javaMacros; }
    public static boolean inputCaptured() {
        return (javaMacros != null && javaMacros.active()) || (controller != null && controller.macroRunning());
    }
    public static boolean capturePhysicalInput(long window) {
        return inputCaptured() && window == Minecraft.getInstance().getWindow().handle();
    }
    public static void updateInputCapture() {
        boolean captured = inputCaptured();
        var client = Minecraft.getInstance();
        if (captured != inputWasCaptured) {
            inputWasCaptured = captured;
            KeyMapping.releaseAll();
            ((MouseCaptureAccess) client.mouseHandler).minecontrol$clearInputState();
            if (captured) restoreCursorWhenFocused = false;
            else restoreCursorWhenFocused = client.level != null && client.gui.screen() == null
                    && client.mouseHandler.isMouseGrabbed();
        }
        if (captured) {
            releaseSystemCursor();
        } else if (restoreCursorWhenFocused) {
            if (client.level == null || client.gui.screen() != null || !client.mouseHandler.isMouseGrabbed()) {
                restoreCursorWhenFocused = false;
            } else if (GLFW.glfwGetWindowAttrib(client.getWindow().handle(), GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE) {
                client.mouseHandler.setIgnoreFirstMove();
                GLFW.glfwSetInputMode(client.getWindow().handle(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
                restoreCursorWhenFocused = false;
            }
        }
    }
    /** Release the OS pointer without clearing Minecraft's logical grab required by macro clicks. */
    public static void releaseSystemCursor() {
        if (!inputCaptured()) return;
        long window = Minecraft.getInstance().getWindow().handle();
        if (GLFW.glfwGetInputMode(window, GLFW.GLFW_CURSOR) != GLFW.GLFW_CURSOR_NORMAL)
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
    }
    static void pollStopShortcut(IntPredicate rawKeyboardDown, IntPredicate rawMouseDown) {
        if (!inputCaptured() || stopMacro == null) return;
        var key = stopMacro.isUnbound() ? InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_F9)
                : KeyMappingHelper.getBoundKeyOf(stopMacro);
        boolean pressed = key.getType() == InputConstants.Type.KEYSYM ? rawKeyboardDown.test(key.getValue())
                : key.getType() == InputConstants.Type.MOUSE && rawMouseDown.test(key.getValue());
        if (pressed) stopRunningMacros();
    }
    private static void pollStopShortcut() {
        long window = Minecraft.getInstance().getWindow().handle();
        if (GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_FOCUSED) != GLFW.GLFW_TRUE) return;
        // Do not use InputConstants.isKeyDown: it intentionally hides physical keys from macros.
        pollStopShortcut(key -> GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS,
                button -> GLFW.glfwGetMouseButton(window, button) == GLFW.GLFW_PRESS);
    }
    public static void handleStopKey(KeyEvent event) {
        if (stopMacro != null && (stopMacro.matches(event)
                || (stopMacro.isUnbound() && event.key() == GLFW.GLFW_KEY_F9))) stopRunningMacros();
    }
    public static void handleStopMouse(MouseButtonEvent event) {
        if (stopMacro != null && stopMacro.matchesMouse(event)) stopRunningMacros();
    }
    public static void stopRunningMacros() {
        if (javaMacros != null) javaMacros.stop();
        if (controller != null) controller.stopMacros();
    }
    public static String stopKeyLabel() {
        return stopMacro == null || stopMacro.isUnbound() ? "F9" : stopMacro.getTranslatedKeyMessage().getString();
    }
    public static void drawMacroStatus(GuiGraphicsExtractor graphics) {
        if (!inputCaptured()) return;
        var client = Minecraft.getInstance();
        String name = javaMacros != null && javaMacros.active() ? javaMacros.activeFile() : "JSON 宏";
        int maxWidth = Math.max(80, client.getWindow().getGuiScaledWidth() - 24);
        String running = client.font.plainSubstrByWidth("运行中：" + name, maxWidth);
        String hint = client.font.plainSubstrByWidth("停止：" + stopKeyLabel() + "  |  光标已释放，手动输入已屏蔽", maxWidth);
        int panelWidth = Math.max(client.font.width(running), client.font.width(hint)) + 12;
        graphics.fill(5, 5, 5 + panelWidth, 36, 0xDD142230);
        graphics.fill(5, 5, 7, 36, 0xFF73DBA1);
        graphics.text(client.font, running, 12, 10, 0xFFB5F5CF);
        graphics.text(client.font, hint, 12, 23, 0xFFFFFFFF);
    }
    public static boolean backgroundEnabled() { return background; }
    public static boolean isHeld(long window, int key) {
        return controller != null && controller.isHeld(window, key);
    }
    @Override public void onInitializeClient() {
        stopMacro = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.minecontrol.stop_macro", GLFW.GLFW_KEY_F9, KeyMapping.Category.MISC));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("minecontrol", "running_macro"),
                (graphics, delta) -> drawMacroStatus(graphics));
        var openMacros = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.minecontrol.macros", GLFW.GLFW_KEY_F8, KeyMapping.Category.MISC));
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof TitleScreen || screen instanceof PauseScreen) {
                Screens.getWidgets(screen).add(Button.builder(Component.literal("MineControl 宏"),
                        button -> client.gui.setScreen(new MacroScreen(screen))).bounds(5, 5, 110, 20).build());
            }
        });
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            try {
                Config config = Config.load(FabricLoader.getInstance().getConfigDir().resolve("minecontrol.json"));
                if (!config.enabled) return;
                controller = new Controller(client, config);
                server = new LocalServer(config, controller);
                server.start();
                javaMacros = new JavaMacroManager(FabricLoader.getInstance().getConfigDir()
                        .resolve("minecontrol-programs"), config.port, () -> {
                    var request = new JsonObject();
                    request.addProperty("id", "java-macro-cleanup");
                    request.addProperty("command", "input.reset");
                    if (controller != null) controller.submit(request);
                }, line -> LOG.info("[Java 宏] {}", line));
                oldPause = client.options.pauseOnLostFocus;
                background = config.backgroundControl;
                if (background) client.options.pauseOnLostFocus = false;
                LOG.info("MineControl listening on 127.0.0.1:{}", config.port);
            } catch (Exception e) {
                LOG.error("Cannot start MineControl; check config and port", e);
                if (server != null) server.close();
                controller = null;
            }
        });
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (previousLevel != null && previousLevel != client.level && javaMacros != null) javaMacros.stop();
            previousLevel = client.level;
            pollStopShortcut();
            if (controller != null) controller.tick();
            while (stopMacro.consumeClick()) { /* Native callbacks and raw polling handle physical stop presses. */ }
            while (openMacros.consumeClick()) {
                if (!(client.gui.screen() instanceof MacroScreen))
                    client.gui.setScreen(new MacroScreen(client.gui.screen()));
            }
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (javaMacros != null) javaMacros.close();
            if (server != null) server.close();
            if (controller != null) controller.stop();
            if (background) client.options.pauseOnLostFocus = oldPause;
            background = false;
            controller = null;
            inputWasCaptured = false;
            restoreCursorWhenFocused = false;
        });
    }
}
