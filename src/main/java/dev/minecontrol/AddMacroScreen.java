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
    public AddMacroScreen(MacroScreen parent) { super(Text.literal("添加宏文件")); this.parent = parent; }
    @Override protected void init() {
        path = addDrawableChild(new TextFieldWidget(textRenderer, 20, 80, width - 40, 20, Text.literal("宏文件完整路径")));
        path.setMaxLength(4096);
        addDrawableChild(ButtonWidget.builder(Text.literal("添加"), button -> {
            try {
                String value = path.getText().strip();
                if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) value = value.substring(1, value.length() - 1);
                if (value.isBlank()) { error = "请填写文件的完整路径，或将文件拖入窗口"; return; }
                if (value.startsWith("~/")) value = System.getProperty("user.home") + value.substring(1);
                parent.importFiles(List.of(Path.of(value)));
                client.setScreen(parent);
            } catch (RuntimeException e) { error = MacroScreen.errorMessage(e); }
        }).dimensions(width / 2 - 104, 120, 100, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("取消"), button -> close())
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
        graphics.drawCenteredTextWithShadow(textRenderer, "粘贴文件完整路径，或直接拖入 .java / .jar 文件", width / 2, 48, 0xFFCBD5E1);
        graphics.drawTextWithShadow(textRenderer, "文件路径", 20, 66, 0xFF94A3B8);
        graphics.drawTextWrapped(textRenderer, Text.literal(error.isEmpty()
                ? "文件将复制到宏目录，同名文件自动改名。删除仅移入回收站。" : error),
                20, 157, width - 40, error.isEmpty() ? 0xFF94A3B8 : 0xFFFFAA88);
    }
}
