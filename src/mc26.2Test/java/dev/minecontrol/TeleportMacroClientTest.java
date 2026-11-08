package dev.minecontrol;

import com.google.gson.JsonObject;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Opt-in real integrated-server dimension transfers; never installed in Prism. */
public final class TeleportMacroClientTest implements ClientModInitializer {
    private final Path config = FabricLoader.getInstance().getConfigDir();
    private CompletableFuture<?> pending;
    private int stage, settled;
    private long started;
    private Object originalLevel;
    private Object connection;
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try { tick(client); }
            catch (Throwable e) {
                MineControlClient.LOG.error("Teleport macro regression failed", e);
                MineControlClient.stopRunningMacros();
                report("failed", e.toString());
                stage = -1;
            }
        });
    }
    private void tick(Minecraft client) throws Exception {
        if (stage < 0) return;
        if (stage == 0) {
            if (client.level == null || client.getSingleplayerServer() == null || !Files.exists(config.resolve("teleport-macro-test.trigger"))) return;
            Files.delete(config.resolve("teleport-macro-test.trigger"));
            started = System.nanoTime();
            originalLevel = client.level;
            connection = client.getConnection();
            Path source = config.resolve("TeleportMacroProbe.java");
            Files.writeString(source, "class TeleportMacroProbe { public static void main(String[] args) throws Exception { Thread.sleep(120000); } }");
            pending = MineControlClient.javaMacros().addFiles(List.of(source));
            stage = 1;
            return;
        }
        if (System.nanoTime() - started > 90_000_000_000L) throw new AssertionError("Teleport test timed out at " + stage);
        if (pending != null) {
            if (!pending.isDone()) return;
            pending.get(); pending = null;
        }
        switch (stage) {
            case 1 -> {
                client.gui.setScreen(null);
                if (!MineControlClient.javaMacros().start("TeleportMacroProbe.java")) throw new AssertionError("Cannot start macro");
                stage = 2;
            }
            case 2 -> { teleport(client, "minecraft:the_nether"); stage = 3; }
            case 3 -> {
                assertActive(client);
                if (client.level == null || !client.level.dimension().identifier().toString().equals("minecraft:the_nether")) return;
                if (client.level == originalLevel) throw new AssertionError("ClientLevel was not replaced");
                if (++settled < 10) return;
                teleport(client, "minecraft:overworld");
                settled = 0; stage = 4;
            }
            case 4 -> {
                assertActive(client);
                if (client.level == null || !client.level.dimension().identifier().toString().equals("minecraft:overworld")) return;
                if (++settled < 10) return;
                // Exercise the exact Fabric disconnect callback used by a real server disconnect.
                ClientPlayConnectionEvents.DISCONNECT.invoker().onPlayDisconnect(client.getConnection(), client);
                stage = 5; settled = 0;
            }
            case 5 -> {
                if (MineControlClient.javaMacros().active()) {
                    if (++settled > 100) throw new AssertionError("Disconnect did not stop macro");
                    return;
                }
                report("passed", "Macro and input capture survive two real dimension transfers; disconnect callback stops macro");
                MineControlClient.LOG.info("Teleport macro in-client checks passed");
                stage = -1;
            }
        }
    }
    private void assertActive(Minecraft client) {
        if (!MineControlClient.javaMacros().active() || !MineControlClient.inputCaptured()) throw new AssertionError("Dimension transfer stopped macro/input capture");
        if (client.getConnection() != connection) throw new AssertionError("Unexpected session replacement");
    }
    private void teleport(Minecraft client, String dimension) {
        var server = client.getSingleplayerServer();
        String name = client.player.getName().getString();
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                "execute in " + dimension + " run tp " + name + " 0 100 0"));
    }
    private void report(String state, String detail) {
        try {
            var result = new JsonObject(); result.addProperty("state", state); result.addProperty("detail", detail);
            Files.writeString(config.resolve("teleport-macro-test-report.json"), result.toString());
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
