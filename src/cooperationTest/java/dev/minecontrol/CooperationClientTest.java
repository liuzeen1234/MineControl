package dev.minecontrol;

import com.google.gson.*;
import java.nio.file.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;

/** Explicit opt-in test bridge; does not install or extract any macros. */
public final class CooperationClientTest implements ClientModInitializer {
    private long ticks;
    public void onInitializeClient() {
        var config = FabricLoader.getInstance().getConfigDir();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            var manager = MineControlClient.javaMacros();
            if (manager == null) return;
            try {
                var trigger = config.resolve("cooperation-start.trigger");
                if (Files.exists(trigger)) {
                    Files.delete(trigger); manager.resume();
                    manager.refresh().thenRun(() -> manager.start("cooperation-scheduler.jar"));
                }
                if (++ticks % 5 == 0) {
                    var state = new JsonObject(); state.addProperty("active", manager.active());
                    state.addProperty("captured", MineControlClient.inputCaptured());
                    state.addProperty("state", manager.state());
                    state.add("logs", new Gson().toJsonTree(manager.logs()));
                    var file = config.resolve("cooperation-state.json");
                    Files.writeString(file, state.toString());
                }
            } catch (Exception error) { MineControlClient.LOG.error("Cooperation probe failed", error); }
        });
    }
}
