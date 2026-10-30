package dev.minecontrol;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.nio.file.Path;
import java.util.List;

/** Paste a path or drop files; import copies them into the managed macro folder. */
public final class AddMacroScreen extends Screen {
    private final MacroScreen parent;
    private EditBox path;
    private String error = "";
    public AddMacroScreen(MacroScreen parent) { super(Component.literal("添加宏文件")); this.parent = parent; }
    @Override protected void init() {
        path = addRenderableWidget(new EditBox(font, 20, 80, width - 40, 20, Component.literal("宏文件完整路径")));
        path.setMaxLength(4096);
        addRenderableWidget(Button.builder(Component.literal("添加"), button -> {
            try {
                String value = path.getValue().strip();
                if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) value = value.substring(1, value.length() - 1);
                if (value.isBlank()) { error = "请填写文件的完整路径，或将文件拖入窗口"; return; }
                if (value.startsWith("~/")) value = System.getProperty("user.home") + value.substring(1);
                parent.importFiles(List.of(Path.of(value)));
                minecraft.gui.setScreen(parent);
            } catch (RuntimeException e) { error = MacroScreen.errorMessage(e); }
        }).bounds(width / 2 - 104, 120, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("取消"), button -> onClose())
                .bounds(width / 2 + 4, 120, 100, 20).build());
        setInitialFocus(path);
    }
    @Override public void onFilesDrop(List<Path> files) { parent.importFiles(files); minecraft.gui.setScreen(parent); }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 22, 0xFFFFFFFF);
        graphics.centeredText(font, "粘贴文件完整路径，或直接拖入 .java / .jar 文件", width / 2, 48, 0xFFCBD5E1);
        graphics.text(font, "文件路径", 20, 66, 0xFF94A3B8);
        graphics.textWithWordWrap(font, Component.literal(error.isEmpty()
                ? "文件将复制到宏目录，同名文件自动改名。删除仅移入回收站。" : error),
                20, 157, width - 40, error.isEmpty() ? 0xFF94A3B8 : 0xFFFFAA88);
    }
}
