package dev.minecontrol;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Full-screen settings, opened exclusively from the macro manager. */
public final class SettingsScreen extends Screen {
    private final Screen parent;
    private String error = "";
    private int contentX;

    public SettingsScreen(Screen parent) {
        super(Component.literal("MineControl · 设置"));
        this.parent = parent;
    }

    @Override protected void init() {
        int sidebarWidth = Math.min(110, width / 4);
        contentX = sidebarWidth + 24;
        Button category = addRenderableWidget(Button.builder(Component.literal("宏与输入"), button -> {})
                .bounds(12, 40, sidebarWidth, 20).build());
        category.active = false;
        Button toggle = addRenderableWidget(Button.builder(toggleLabel(), button -> {
            try {
                MineControlClient.setBlockPhysicalInputDuringMacros(!MineControlClient.blockPhysicalInputDuringMacros());
                error = "";
            } catch (java.io.IOException e) {
                error = "保存失败，请重试；设置未更改";
                MineControlClient.LOG.error("Cannot save MineControl settings", e);
            }
            button.setMessage(toggleLabel());
        }).bounds(contentX, 64, Math.max(100, width - contentX - 12), 20)
                .tooltip(Tooltip.create(Component.literal("阻止手动操作干扰宏，停止快捷键仍可使用。修改立即生效并保存。")))
                .build());
        toggle.active = MineControlClient.settingsAvailable();
        if (!toggle.active) error = "配置未成功加载，请检查客户端日志";
        addRenderableWidget(Button.builder(Component.literal("返回"), button -> onClose())
                .bounds(width / 2 - 50, height - 28, 100, 20).build());
    }

    private Component toggleLabel() {
        return Component.literal("宏运行时屏蔽键鼠输入：" + (MineControlClient.blockPhysicalInputDuringMacros() ? "开启" : "关闭"));
    }

    private void line(GuiGraphicsExtractor graphics, String value, int y, int color) {
        graphics.text(font, font.plainSubstrByWidth(value, Math.max(0, width - contentX - 12)), contentX, y, color);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);
        graphics.fill(contentX - 8, 36, contentX - 7, height - 40, 0xFF465468);
        line(graphics, "宏与输入", 43, 0xFFFFFFFF);
        line(graphics, "阻止手动操作干扰宏，停止快捷键仍可使用。", 94, 0xFFCBD5E1);
        line(graphics, "适用于执行宏和 JSON 宏。", 110, 0xFF94A3B8);
        line(graphics, "仅运行检测宏、调度宏时不屏蔽。", 126, 0xFF94A3B8);
        line(graphics, "修改立即生效并自动保存。", 148, 0xFF94A3B8);
        if (!error.isEmpty()) line(graphics, error, height - 44, 0xFFFF8888);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
}
