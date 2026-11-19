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
        super(Component.translatable("minecontrol.settings.title"));
        this.parent = parent;
    }

    @Override protected void init() {
        int sidebarWidth = Math.min(110, width / 4);
        contentX = sidebarWidth + 24;
        Button category = addRenderableWidget(Button.builder(Component.translatable("minecontrol.settings.category"), button -> {})
                .bounds(12, 40, sidebarWidth, 20).build());
        category.active = false;
        Button toggle = addRenderableWidget(Button.builder(toggleLabel(), button -> {
            try {
                MineControlClient.setBlockPhysicalInputDuringMacros(!MineControlClient.blockPhysicalInputDuringMacros());
                error = "";
            } catch (java.io.IOException e) {
                error = UiText.get("minecontrol.settings.save_failed");
                MineControlClient.LOG.error("Cannot save MineControl settings", e);
            }
            button.setMessage(toggleLabel());
        }).bounds(contentX, 64, Math.max(100, width - contentX - 12), 20)
                .tooltip(Tooltip.create(Component.translatable("minecontrol.settings.tooltip")))
                .build());
        toggle.active = MineControlClient.settingsAvailable();
        if (!toggle.active) error = UiText.get("minecontrol.settings.unavailable");
        addRenderableWidget(Button.builder(Component.translatable("minecontrol.back"), button -> onClose())
                .bounds(width / 2 - 50, height - 28, 100, 20).build());
    }

    private Component toggleLabel() {
        return Component.literal(UiText.get("minecontrol.settings.toggle") + (MineControlClient.blockPhysicalInputDuringMacros() ? UiText.get("minecontrol.on") : UiText.get("minecontrol.off")));
    }

    private void line(GuiGraphicsExtractor graphics, String value, int y, int color) {
        graphics.text(font, font.plainSubstrByWidth(value, Math.max(0, width - contentX - 12)), contentX, y, color);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);
        graphics.fill(contentX - 8, 36, contentX - 7, height - 40, 0xFF465468);
        line(graphics, UiText.get("minecontrol.settings.category"), 43, 0xFFFFFFFF);
        line(graphics, UiText.get("minecontrol.settings.description"), 94, 0xFFCBD5E1);
        line(graphics, UiText.get("minecontrol.settings.scope"), 110, 0xFF94A3B8);
        line(graphics, UiText.get("minecontrol.settings.detectors"), 126, 0xFF94A3B8);
        line(graphics, UiText.get("minecontrol.settings.saved"), 148, 0xFF94A3B8);
        if (!error.isEmpty()) line(graphics, error, height - 44, 0xFFFF8888);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
}
