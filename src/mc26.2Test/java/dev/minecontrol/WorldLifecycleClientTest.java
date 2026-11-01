package dev.minecontrol;

import com.google.gson.*;
import java.nio.file.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.LevelLoadTracker;

/** Opt-in bridge for deterministic loading screens and real transfers in an isolated test world. */
public final class WorldLifecycleClientTest implements ClientModInitializer {
    @Override public void onInitializeClient() {
        Path config = FabricLoader.getInstance().getConfigDir();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            Path trigger = config.resolve("world-lifecycle.request.json");
            if (!Files.exists(trigger)) return;
            JsonObject report = new JsonObject();
            try {
                JsonObject request = JsonParser.parseString(Files.readString(trigger)).getAsJsonObject();
                Files.delete(trigger);
                report.add("id", request.get("id"));
                switch (request.get("action").getAsString()) {
                    case "loading" -> client.gui.setScreen(new LevelLoadingScreen(new LevelLoadTracker(), LevelLoadingScreen.Reason.OTHER) { @Override public void tick() {} });
                    case "prepare" -> {
                        if (!client.player.isAlive()) client.player.respawn();
                        var server = client.getSingleplayerServer();
                        String name = client.player.getName().getString();
                        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "gamemode spectator " + name));
                    }
                    case "ready" -> client.gui.setScreen(null);
                    case "start" -> {
                        if (!MineControlClient.javaMacros().start(request.get("file").getAsString()))
                            throw new AssertionError("Cannot start test macro");
                    }
                    case "teleport" -> {
                        var server = client.getSingleplayerServer();
                        String command = "execute in " + request.get("dimension").getAsString()
                                + " run tp " + client.player.getName().getString() + " 0 100 0";
                        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command));
                    }
                    case "disconnect" -> client.disconnect(new TitleScreen(), false);
                    default -> throw new IllegalArgumentException("Unknown lifecycle test action");
                }
                report.addProperty("ok", true);
            } catch (Throwable e) { report.addProperty("ok", false); report.addProperty("error", e.toString()); }
            try { Files.writeString(config.resolve("world-lifecycle.response.json"), report.toString()); }
            catch (Exception e) { MineControlClient.LOG.error("Cannot write lifecycle probe report", e); }
        });
    }
}
