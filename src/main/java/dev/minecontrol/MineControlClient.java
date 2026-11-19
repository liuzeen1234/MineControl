package dev.minecontrol;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.text.Text;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import org.lwjgl.glfw.GLFW;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.InputUtil;
import java.util.function.IntPredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
public final class MineControlClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("minecontrol");
    private static Config settings = new Config();
    private static boolean settingsLoaded;
    private static Controller controller;
    private static LocalServer server;
    private static boolean background;
    private static boolean oldPause;
    private static JavaMacroManager javaMacros;
    private static KeyBinding stopMacro;
    private static KeyBinding openMacros;
    private static boolean inputWasCaptured;
    private static boolean restoreCursorWhenFocused;
    public static JavaMacroManager javaMacros() { return javaMacros; }
    public static boolean inputCaptured() {
        return settings.blockPhysicalInputDuringMacros && ((javaMacros != null && javaMacros.executing())
                || (controller != null && controller.macroRunning()));
    }
    public static boolean capturePhysicalInput(long window) {
        var client = MinecraftClient.getInstance();
        return inputCaptured() && window == client.getWindow().getHandle()
                && !(client.currentScreen instanceof MacroScreen) && !(client.currentScreen instanceof AddMacroScreen)
                && !(client.currentScreen instanceof SettingsScreen);
    }
    public static boolean blockPhysicalInputDuringMacros() { return settings.blockPhysicalInputDuringMacros; }
    public static boolean settingsAvailable() { return settingsLoaded; }
    public static void setBlockPhysicalInputDuringMacros(boolean value) throws java.io.IOException {
        boolean previous = settings.blockPhysicalInputDuringMacros;
        settings.blockPhysicalInputDuringMacros = value;
        try {
            settings.save(FabricLoader.getInstance().getConfigDir().resolve("minecontrol.json"));
        } catch (java.io.IOException e) {
            settings.blockPhysicalInputDuringMacros = previous;
            throw e;
        }
        updateInputCapture();
    }
    public static void updateInputCapture() {
        boolean captured = inputCaptured();
        var client = MinecraftClient.getInstance();
        if (captured != inputWasCaptured) {
            inputWasCaptured = captured;
            KeyBinding.unpressAll();
            ((MouseCaptureAccess) client.mouse).minecontrol$clearInputState();
            if (captured) restoreCursorWhenFocused = false;
            else restoreCursorWhenFocused = client.world != null && client.currentScreen == null
                    && client.mouse.isCursorLocked();
        }
        if (captured) {
            releaseSystemCursor();
        } else if (restoreCursorWhenFocused) {
            if (client.world == null || client.currentScreen != null || !client.mouse.isCursorLocked()) {
                restoreCursorWhenFocused = false;
            } else if (GLFW.glfwGetWindowAttrib(client.getWindow().getHandle(), GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE) {
                client.mouse.setResolutionChanged();
                GLFW.glfwSetInputMode(client.getWindow().getHandle(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
                restoreCursorWhenFocused = false;
            }
        }
    }
    /** Release the OS pointer without clearing Minecraft's logical grab required by macro clicks. */
    public static void releaseSystemCursor() {
        if (!inputCaptured()) return;
        long window = MinecraftClient.getInstance().getWindow().getHandle();
        if (GLFW.glfwGetInputMode(window, GLFW.GLFW_CURSOR) != GLFW.GLFW_CURSOR_NORMAL)
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
    }
    static void pollStopShortcut(IntPredicate rawKeyboardDown, IntPredicate rawMouseDown) {
        if ((!inputCaptured() && (javaMacros == null || !javaMacros.active()) && (controller == null || !controller.coordinator().active())) || stopMacro == null) return;
        var key = stopMacro.isUnbound() ? InputUtil.Type.KEYSYM.createFromCode(GLFW.GLFW_KEY_F9)
                : KeyBindingHelper.getBoundKeyOf(stopMacro);
        boolean pressed = key.getCategory() == InputUtil.Type.KEYSYM ? rawKeyboardDown.test(key.getCode())
                : key.getCategory() == InputUtil.Type.MOUSE && rawMouseDown.test(key.getCode());
        if (pressed) stopRunningMacros();
    }
    private static void pollStopShortcut() {
        long window = MinecraftClient.getInstance().getWindow().getHandle();
        if (GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_FOCUSED) != GLFW.GLFW_TRUE) return;
        // Do not use InputUtil.isKeyDown: it intentionally hides physical keys from macros.
        pollStopShortcut(key -> GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS,
                button -> GLFW.glfwGetMouseButton(window, button) == GLFW.GLFW_PRESS);
    }
    public static void handleStopKey(int key, int scanCode) {
        if (stopMacro != null && (stopMacro.matchesKey(key, scanCode)
                || (stopMacro.isUnbound() && key == GLFW.GLFW_KEY_F9))) stopRunningMacros();
    }
    public static boolean handleOpenMacrosKey(int key, int scanCode) {
        if (openMacros != null && openMacros.matchesKey(key, scanCode)) {
            var client = MinecraftClient.getInstance();
            client.setScreen(new MacroScreen(client.currentScreen));
            return true;
        }
        return false;
    }
    public static void handleStopMouse(int button) {
        if (stopMacro != null && stopMacro.matchesMouse(button)) stopRunningMacros();
    }
    public static void stopRunningMacros() {
        stopRunningMacros(UiText.get("minecontrol.stop.reason"));
    }
    private static void stopRunningMacros(String reason) {
        if (controller != null) controller.coordinator().emergency(reason);
        else if (javaMacros != null) javaMacros.stop();
    }
    public static String stopKeyLabel() {
        return stopMacro == null || stopMacro.isUnbound() ? "F9" : stopMacro.getBoundKeyLocalizedText().getString();
    }
    public static void drawMacroStatus(DrawContext graphics) {
        if (!inputCaptured() && (javaMacros == null || !javaMacros.active()) && (controller == null || !controller.coordinator().active())) return;
        var client = MinecraftClient.getInstance();
        String name = javaMacros != null && javaMacros.active() ? UiText.status(javaMacros.state()) : controller != null && controller.coordinator().active() ? UiText.status(controller.coordinator().summary()) : UiText.get("minecontrol.hud.json");
        int maxWidth = Math.max(80, client.getWindow().getScaledWidth() - 24);
        String running = client.textRenderer.trimToWidth(UiText.get("minecontrol.hud.running") + name, maxWidth);
        String hint = client.textRenderer.trimToWidth(UiText.get("minecontrol.hud.stop") + stopKeyLabel() + (inputCaptured() ? UiText.get("minecontrol.hud.blocked") : UiText.get("minecontrol.hud.allowed")), maxWidth);
        int panelWidth = Math.max(client.textRenderer.getWidth(running), client.textRenderer.getWidth(hint)) + 12;
        graphics.fill(5, 5, 5 + panelWidth, 36, 0xDD142230);
        graphics.fill(5, 5, 7, 36, 0xFF73DBA1);
        graphics.drawTextWithShadow(client.textRenderer, running, 12, 10, 0xFFB5F5CF);
        graphics.drawTextWithShadow(client.textRenderer, hint, 12, 23, 0xFFFFFFFF);
    }
    public static boolean backgroundEnabled() { return background; }
    public static boolean isHeld(long window, int key) {
        return controller != null && controller.isHeld(window, key);
    }
    @Override public void onInitializeClient() {
        UiText.setResolver(key -> net.minecraft.client.resource.language.I18n.translate(key));
        stopMacro = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.minecontrol.stop_macro", GLFW.GLFW_KEY_F9, "key.categories.misc"));
        HudRenderCallback.EVENT.register(
                (graphics, delta) -> drawMacroStatus(graphics));
        openMacros = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.minecontrol.macros", GLFW.GLFW_KEY_F8, "key.categories.misc"));
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            ScreenEvents.afterRender(screen).register((current, graphics, mouseX, mouseY, delta) -> drawMacroStatus(graphics));
            if (screen instanceof TitleScreen || screen instanceof GameMenuScreen) {
                Screens.getButtons(screen).add(ButtonWidget.builder(Text.translatable("minecontrol.macros.open"),
                        button -> client.setScreen(new MacroScreen(screen))).dimensions(5, 5, 110, 20).build());
            }
        });
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            try {
                Config config = Config.load(FabricLoader.getInstance().getConfigDir().resolve("minecontrol.json"));
                settings = config;
                settingsLoaded = true;
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
                javaMacros.coordinator(controller.coordinator());
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
        // A dimension transfer replaces ClientLevel without ending the server session.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { if (controller != null) controller.worldExited(); });
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            pollStopShortcut();
            if (controller != null) controller.tick();
            updateInputCapture();
            while (stopMacro.wasPressed()) { /* Native callbacks and raw polling handle physical stop presses. */ }
            while (openMacros.wasPressed()) {
                if (!(client.currentScreen instanceof MacroScreen))
                    client.setScreen(new MacroScreen(client.currentScreen));
            }
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (controller != null) controller.coordinator().emergency("客户端退出");
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
