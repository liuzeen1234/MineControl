package dev.minecontrol;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
public final class MineControlClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("minecontrol");
    private static Controller controller;
    private static LocalServer server;
    private static boolean background;
    private static boolean oldPause;
    public static boolean backgroundEnabled() { return background; }
    public static boolean isHeld(long window, int key) {
        return controller != null && controller.isHeld(window, key);
    }
    @Override public void onInitializeClient() {
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            try {
                Config config = Config.load(FabricLoader.getInstance().getConfigDir().resolve("minecontrol.json"));
                if (!config.enabled) return;
                controller = new Controller(client, config);
                server = new LocalServer(config, controller);
                server.start();
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
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { if (controller != null) controller.worldExited(); });
        ClientTickEvents.START_CLIENT_TICK.register(client -> { if (controller != null) controller.tick(); });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (server != null) server.close();
            if (controller != null) controller.stop();
            if (background) client.options.pauseOnLostFocus = oldPause;
            background = false;
            controller = null;
        });
    }
}
