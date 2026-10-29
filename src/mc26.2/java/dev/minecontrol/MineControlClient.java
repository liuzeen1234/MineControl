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
    public static JavaMacroManager javaMacros() { return javaMacros; }
    public static boolean backgroundEnabled() { return background; }
    public static boolean isHeld(long window, int key) {
        return controller != null && controller.isHeld(window, key);
    }
    @Override public void onInitializeClient() {
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
                });
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
            if (controller != null) controller.tick();
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
        });
    }
}
