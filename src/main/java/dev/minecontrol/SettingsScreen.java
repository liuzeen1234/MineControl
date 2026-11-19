package dev.minecontrol;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/** Full-screen settings, opened exclusively from the macro manager. */
public final class SettingsScreen extends Screen {
    private final Screen parent;
    private String error = "";
    private int contentX;

    public SettingsScreen(Screen parent) {
        super(Text.translatable("minecontrol.settings.title"));
        this.parent = parent;
    }

    @Override protected void init() {
        int sidebarWidth = Math.min(110, width / 4);
        contentX = sidebarWidth + 24;
        ButtonWidget category = addDrawableChild(ButtonWidget.builder(Text.translatable("minecontrol.settings.category"), button -> {})
                .dimensions(12, 40, sidebarWidth, 20).build());
        category.active = false;
        ButtonWidget toggle = addDrawableChild(ButtonWidget.builder(toggleLabel(), button -> {
            try {
                MineControlClient.setBlockPhysicalInputDuringMacros(!MineControlClient.blockPhysicalInputDuringMacros());
                error = "";
            } catch (java.io.IOException e) {
                error = UiText.get("minecontrol.settings.save_failed");
                MineControlClient.LOG.error("Cannot save MineControl settings", e);
            }
            button.setMessage(toggleLabel());
        }).dimensions(contentX, 64, Math.max(100, width - contentX - 12), 20)
                .tooltip(Tooltip.of(Text.translatable("minecontrol.settings.tooltip")))
                .build());
        toggle.active = MineControlClient.settingsAvailable();
        if (!toggle.active) error = UiText.get("minecontrol.settings.unavailable");
        addDrawableChild(ButtonWidget.builder(Text.translatable("minecontrol.back"), button -> close())
                .dimensions(width / 2 - 50, height - 28, 100, 20).build());
    }

    private Text toggleLabel() {
        return Text.literal(UiText.get("minecontrol.settings.toggle") + (MineControlClient.blockPhysicalInputDuringMacros() ? UiText.get("minecontrol.on") : UiText.get("minecontrol.off")));
    }

    private void line(DrawContext graphics, String value, int y, int color) {
        graphics.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(value, Math.max(0, width - contentX - 12)), contentX, y, color);
    }

    @Override public void render(DrawContext graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xEE101820);
        super.render(graphics, mouseX, mouseY, delta);
        graphics.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, 0xFFFFFFFF);
        graphics.fill(contentX - 8, 36, contentX - 7, height - 40, 0xFF465468);
        line(graphics, UiText.get("minecontrol.settings.category"), 43, 0xFFFFFFFF);
        line(graphics, UiText.get("minecontrol.settings.description"), 94, 0xFFCBD5E1);
        line(graphics, UiText.get("minecontrol.settings.scope"), 110, 0xFF94A3B8);
        line(graphics, UiText.get("minecontrol.settings.detectors"), 126, 0xFF94A3B8);
        line(graphics, UiText.get("minecontrol.settings.saved"), 148, 0xFF94A3B8);
        if (!error.isEmpty()) line(graphics, error, height - 44, 0xFFFF8888);
    }

    @Override public boolean shouldPause() { return false; }
    @Override public void close() { client.setScreen(parent); }
}
