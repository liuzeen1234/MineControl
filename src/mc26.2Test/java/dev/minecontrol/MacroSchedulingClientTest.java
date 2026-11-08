package dev.minecontrol;

import com.google.gson.*;
import java.nio.file.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.glfw.GLFW;

/** Opt-in observation/control probe for the isolated scheduling regression client; never shipped. */
public final class MacroSchedulingClientTest implements ClientModInitializer {
    @Override public void onInitializeClient() {
        Path config = FabricLoader.getInstance().getConfigDir();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            Path trigger = config.resolve("macro-scheduling-test.trigger");
            if (!Files.exists(trigger)) return;
            try {
                JsonObject request = JsonParser.parseString(Files.readString(trigger)).getAsJsonObject();
                Files.delete(trigger);
                String action = request.get("action").getAsString();
                if (action.equals("console")) MineControlClient.handleOpenMacrosKey(new net.minecraft.client.input.KeyEvent(GLFW.GLFW_KEY_F8, 0, 0));
                if (action.equals("close_console")) client.gui.setScreen(null);
                if (action.equals("start_program")) MineControlClient.javaMacros().refresh().thenRun(() -> MineControlClient.javaMacros().start(request.get("file").getAsString()));
                if (action.equals("resume")) MineControlClient.javaMacros().resume();
                if (action.equals("stop")) MineControlClient.pollStopShortcut(key -> key == GLFW.GLFW_KEY_F9, button -> false);
                MineControlClient.updateInputCapture();
                JsonObject report = new JsonObject(); report.add("id", request.get("id"));
                report.addProperty("physical_blocked", MineControlClient.capturePhysicalInput(client.getWindow().handle()));
                report.addProperty("console_open", client.gui.screen() instanceof MacroScreen);
                report.addProperty("captured", MineControlClient.inputCaptured());
                report.addProperty("shift_down", client.options.keyShift.isDown());
                report.addProperty("cursor_mode", GLFW.glfwGetInputMode(client.getWindow().handle(), GLFW.GLFW_CURSOR));
                report.addProperty("world_loaded", client.level != null);
                Files.writeString(config.resolve("macro-scheduling-test-observation.json"), report.toString());
            } catch (Exception e) { MineControlClient.LOG.error("Scheduling test probe failed", e); }
        });
    }
}
