package dev.minecontrol;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import java.nio.file.Path;
import java.util.List;

/** Paste a path or drop files; import copies them into the managed macro folder. */
public final class AddMacroScreen extends Screen {
    private final MacroScreen parent;
    private TextFieldWidget path;
    private String error = "";
    public AddMacroScreen(MacroScreen parent) { super(Text.translatable("minecontrol.add.title")); this.parent = parent; }
    @Override protected void init() {
        path = addDrawableChild(new TextFieldWidget(textRenderer, 20, 80, width - 40, 20, Text.translatable("minecontrol.add.path_accessible")));
        path.setMaxLength(4096);
        addDrawableChild(ButtonWidget.builder(Text.translatable("minecontrol.add.submit"), button -> {
            try {
                String value = path.getText().strip();
                if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) value = value.substring(1, value.length() - 1);
                if (value.isBlank()) { error = UiText.get("minecontrol.add.required"); return; }
                if (value.startsWith("~/")) value = System.getProperty("user.home") + value.substring(1);
                parent.importFiles(List.of(Path.of(value)));
                client.setScreen(parent);
            } catch (RuntimeException e) { error = MacroScreen.errorMessage(e); }
        }).dimensions(width / 2 - 104, 120, 100, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("minecontrol.cancel"), button -> close())
                .dimensions(width / 2 + 4, 120, 100, 20).build());
        setInitialFocus(path);
    }
    @Override public void filesDragged(List<Path> files) { parent.importFiles(files); client.setScreen(parent); }
    @Override public void close() { client.setScreen(parent); }
    @Override public boolean shouldPause() { return false; }
    @Override public void render(DrawContext graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xEE101820);
        super.render(graphics, mouseX, mouseY, delta);
        graphics.drawCenteredTextWithShadow(textRenderer, title, width / 2, 22, 0xFFFFFFFF);
        graphics.drawCenteredTextWithShadow(textRenderer, UiText.get("minecontrol.add.hint"), width / 2, 48, 0xFFCBD5E1);
        graphics.drawTextWithShadow(textRenderer, UiText.get("minecontrol.add.path"), 20, 66, 0xFF94A3B8);
        graphics.drawTextWrapped(textRenderer, Text.literal(error.isEmpty()
                ? UiText.get("minecontrol.add.details") : error),
                20, 157, width - 40, error.isEmpty() ? 0xFF94A3B8 : 0xFFFFAA88);
    }
}
