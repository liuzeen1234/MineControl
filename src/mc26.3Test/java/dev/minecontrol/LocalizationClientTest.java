package dev.minecontrol;

import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Opt-in checks against Minecraft's real resource reload and live screen widgets. */
public final class LocalizationClientTest implements ClientModInitializer {
    private final List<String> checks = new ArrayList<>();
    private CompletableFuture<Void> reload;
    private String originalLanguage;
    private AbstractWidget retainedLabel;
    private int stage;

    @Override public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try { tick(client); }
            catch (Throwable error) {
                MineControlClient.LOG.error("Localization test failed", error);
                report("failed", error.toString());
                stage = -1;
            }
        });
    }
    private void tick(Minecraft client) throws Exception {
        if (stage < 0) return;
        var trigger = FabricLoader.getInstance().getConfigDir().resolve("localization-test.trigger");
        if (stage == 0) {
            if (!(client.gui.screen() instanceof TitleScreen) || !Files.exists(trigger)) return;
            Files.delete(trigger);
            originalLanguage = client.options.languageCode;
            language(client, "en_us"); stage = 1; return;
        }
        if (!reload.isDone()) return;
        reload.join();
        if (stage == 1) {
            verifyScreens(client, false);
            retainedLabel = client.gui.screen().children().stream().filter(AbstractWidget.class::isInstance)
                    .map(AbstractWidget.class::cast).filter(w -> w.getMessage().getString().equals("Settings")).findFirst().orElseThrow();
            language(client, "zh_cn"); stage = 2;
        } else if (stage == 2) {
            check(retainedLabel.getMessage().getString().equals("设置"), "existing translatable component follows language reload");
            verifyScreens(client, true);
            language(client, originalLanguage); stage = 3;
        } else {
            client.gui.setScreen(new TitleScreen());
            report("passed", ""); stage = -1;
        }
    }
    private void language(Minecraft client, String code) {
        client.options.languageCode = code;
        client.getLanguageManager().setSelected(code);
        reload = client.reloadResourcePacks();
    }
    private void verifyScreens(Minecraft client, boolean chinese) {
        String locale = chinese ? "zh_cn" : "en_us";
        var macros = new MacroScreen(new TitleScreen());
        client.gui.setScreen(macros);
        check(macros.getTitle().getString().equals(chinese ? "MineControl · 宏文件" : "MineControl · Macro Files"), locale + " macro title");
        check(labels(client).contains(chinese ? "添加文件" : "Add File"), locale + " macro actions");
        client.gui.setScreen(new SettingsScreen(macros));
        check(labels(client).stream().anyMatch(s -> s.startsWith(chinese ? "宏运行时屏蔽键鼠输入：" : "Block input during macros: ")), locale + " input setting");
        client.gui.setScreen(new AddMacroScreen(macros));
        check(client.gui.screen().getTitle().getString().equals(chinese ? "添加宏文件" : "Add Macro File"), locale + " add-file title");
        Button add = client.gui.screen().children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(w -> w.getMessage().getString().equals(chinese ? "添加" : "Add")).findFirst().orElseThrow();
        add.onPress(new net.minecraft.client.input.KeyEvent(0, 0, 0));
        try {
            var field = AddMacroScreen.class.getDeclaredField("error"); field.setAccessible(true);
            check(field.get(client.gui.screen()).equals(chinese ? "请填写文件的完整路径，或将文件拖入窗口" : "Enter the full file path or drop a file here"), locale + " empty-path validation");
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        check(UiText.status("就绪").equals(chinese ? "就绪" : "Ready"), locale + " manager status");
        check(UiText.status("宏文件不存在或类型不支持：中文宏.jar").equals(chinese ? "宏文件不存在或类型不支持：中文宏.jar" : "Macro file missing or unsupported: 中文宏.jar"), locale + " error translation preserves filename");
        check(UiText.status("中文宏.jar").equals("中文宏.jar"), locale + " filename unchanged");
        check(UiText.status("[中文宏.jar] 自定义输出").equals("[中文宏.jar] 自定义输出"), locale + " program output unchanged");
        check(UiText.get("minecontrol.hud.running").equals(chinese ? "运行中：" : "Running: "), locale + " HUD text");
        client.gui.setScreen(macros);
    }
    private List<String> labels(Minecraft client) {
        return client.gui.screen().children().stream().filter(AbstractWidget.class::isInstance)
                .map(AbstractWidget.class::cast).map(w -> w.getMessage().getString()).toList();
    }
    private void check(boolean passed, String label) {
        if (!passed) throw new AssertionError(label);
        checks.add(label);
    }
    private void report(String state, String error) {
        try {
            Files.writeString(FabricLoader.getInstance().getConfigDir().resolve("localization-test-report.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("state", state, "error", error, "checks", checks)));
        } catch (Exception failure) { throw new RuntimeException(failure); }
    }
}
