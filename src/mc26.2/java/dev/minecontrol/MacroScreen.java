package dev.minecontrol;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class MacroScreen extends Screen {
    private final Screen parent;
    private Button start, stop;
    public MacroScreen(Screen parent) {
        super(Component.literal("MineControl 宏控制台"));
        this.parent = parent;
    }
    @Override protected void init() {
        int left = width / 2 - 150;
        start = addRenderableWidget(Button.builder(Component.literal("启动实体点击"), button -> {
            var manager = MineControlClient.javaMacros();
            if (manager != null) {
                manager.start();
                minecraft.gui.setScreen(null);
            }
        }).bounds(left, 100, 145, 20).build());
        stop = addRenderableWidget(Button.builder(Component.literal("停止宏"), button -> {
            var manager = MineControlClient.javaMacros();
            if (manager != null) manager.stop();
        }).bounds(left + 155, 100, 145, 20).build());
        addRenderableWidget(Button.builder(Component.literal("返回"), button -> onClose())
                .bounds(width / 2 - 60, height - 30, 120, 20).build());
        updateButtons();
    }
    private void updateButtons() {
        var manager = MineControlClient.javaMacros();
        start.active = manager != null && !manager.active() && minecraft.level != null;
        stop.active = manager != null && manager.active();
    }
    @Override public void tick() { updateButtons(); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 22, 0xFFFFFFFF);
        graphics.centeredText(font, "准星出现实体 → 随机等待 10–30 tick → 左键一次", width / 2, 44, 0xFFCBD5E1);
        graphics.centeredText(font, "等待后不再检查目标；移开准星后可再次触发。", width / 2, 60, 0xFFCBD5E1);
        var manager = MineControlClient.javaMacros();
        String status = manager == null ? "控制服务不可用，请检查配置和端口" : manager.state();
        graphics.centeredText(font, "状态：" + status, width / 2, 80, 0xFF80D8A0);
        if (minecraft.level == null) graphics.centeredText(font, "请先进入世界再启动", width / 2, 124, 0xFFFFCC80);
        if (manager != null) {
            var logs = manager.logs();
            int rows = Math.max(0, (height - 190) / 12);
            int y = 144;
            for (int i = Math.max(0, logs.size() - rows); i < logs.size(); i++) {
                graphics.text(font, font.plainSubstrByWidth(logs.get(i), width - 40), 20, y, 0xFFCBD5E1);
                y += 12;
            }
        }
    }
}
