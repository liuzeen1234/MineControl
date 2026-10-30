package dev.minecontrol;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import java.nio.file.Path;
import java.util.List;

/** A file browser for the external Java macros in this instance. */
public final class MacroScreen extends Screen {
    private final Screen parent;
    private Button start, stop, delete, add;
    private FileList list;
    private List<MacroFiles.Entry> displayed = List.of();
    private String selected;
    private String message = "";
    private int ticks;
    public MacroScreen(Screen parent) {
        super(Component.literal("MineControl · 宏文件"));
        this.parent = parent;
    }
    private JavaMacroManager manager() { return MineControlClient.javaMacros(); }
    @Override protected void init() {
        int gap = 4, buttonWidth = (width - 24 - gap * 3) / 4;
        add = addRenderableWidget(Button.builder(Component.literal("添加文件"), button ->
                minecraft.gui.setScreen(new AddMacroScreen(this)))
                .bounds(12, 28, buttonWidth, 20).build());
        delete = addRenderableWidget(Button.builder(Component.literal("删除所选"), button -> {
            if (selected == null || manager() == null) return;
            String name = selected;
            manager().deleteFile(name).whenComplete((result, error) -> minecraft.execute(() -> {
                message = error == null ? "已移入 .trash，可从宏文件夹恢复" : errorMessage(error);
                reload();
            }));
        }).bounds(12 + buttonWidth + gap, 28, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("打开文件夹"), button -> {
            if (manager() != null) Util.getPlatform().openPath(manager().directory());
        }).bounds(12 + 2 * (buttonWidth + gap), 28, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("刷新"), button -> {
            if (manager() != null) manager().refresh().exceptionally(error -> {
                minecraft.execute(() -> message = errorMessage(error)); return null;
            });
        }).bounds(12 + 3 * (buttonWidth + gap), 28, buttonWidth, 20).build());
        list = addRenderableWidget(new FileList(Math.max(30, height - 184)));
        start = addRenderableWidget(Button.builder(Component.literal("启动所选"), button -> {
            if (manager() != null && selected != null && manager().start(selected)) minecraft.gui.setScreen(null);
        }).bounds(12, height - 50, (width - 28) / 2, 20).build());
        stop = addRenderableWidget(Button.builder(Component.literal("停止运行"), button -> {
            if (manager() != null) manager().stop();
        }).bounds(16 + (width - 28) / 2, height - 50, (width - 28) / 2, 20).build());
        addRenderableWidget(Button.builder(Component.literal("返回"), button -> onClose())
                .bounds(width / 2 - 50, height - 26, 100, 20).build());
        displayed = List.of();
        reload(); updateButtons();
        if (manager() != null) manager().refresh();
    }
    private void reload() {
        if (manager() == null || list == null) return;
        var entries = manager().entries();
        if (entries.equals(displayed) && !list.children().isEmpty()) return;
        displayed = entries;
        if (selected == null || entries.stream().noneMatch(entry -> entry.name().equals(selected))) {
            String running = manager().active() ? manager().activeFile() : null;
            selected = running != null && entries.stream().anyMatch(entry -> entry.name().equals(running))
                    ? running : entries.isEmpty() ? null : entries.get(0).name();
        }
        double scroll = list.scrollAmount();
        list.replaceEntries(entries.stream().map(Row::new).toList());
        for (Row row : list.children()) if (row.file.name().equals(selected)) list.setSelected(row);
        list.setScrollAmount(scroll);
    }
    private void updateButtons() {
        var manager = manager();
        boolean available = manager != null && !manager.loading();
        start.active = available && selected != null && !manager.active() && minecraft.level != null;
        stop.active = manager != null && manager.active();
        delete.active = available && selected != null && !(manager.active() && selected.equals(manager.activeFile()));
        add.active = available;
    }
    @Override public void tick() {
        reload(); updateButtons();
        if (++ticks % 40 == 0 && manager() != null && !manager().loading()) manager().refresh();
    }
    public void importFiles(List<Path> paths) {
        if (manager() == null) return;
        message = "正在添加文件…";
        manager().addFiles(paths).whenComplete((result, error) -> minecraft.execute(() -> {
            message = error == null ? "文件已添加" : errorMessage(error);
            reload();
        }));
    }
    @Override public void onFilesDrop(List<Path> paths) { importFiles(paths); }
    static String errorMessage(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error.getMessage() == null ? error.toString() : error.getMessage();
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    private void text(GuiGraphicsExtractor graphics, String text, int x, int y, int maxWidth, int color) {
        graphics.text(font, font.plainSubstrByWidth(text, Math.max(0, maxWidth)), x, y, color);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 10, 0xFFFFFFFF);
        String directory = manager() == null ? "控制服务不可用，请检查配置和端口" : "当前目录：config/minecontrol-programs/";
        text(graphics, directory, 12, 53, width - 24, 0xFF94A3B8);
        graphics.fill(12, 66, width - 12, 80, 0xCC243447);
        int rowWidth = width - 44;
        text(graphics, "文件名", 20, 69, rowWidth / 2, 0xFFFFFFFF);
        text(graphics, "类型", 20 + rowWidth / 2, 69, rowWidth / 5, 0xFFFFFFFF);
        text(graphics, "大小", 20 + rowWidth * 7 / 10, 69, rowWidth / 8, 0xFFFFFFFF);
        text(graphics, "状态", 20 + rowWidth * 83 / 100, 69, rowWidth / 6, 0xFFFFFFFF);
        if (displayed.isEmpty()) graphics.centeredText(font,
                manager() != null && manager().loading() ? "正在读取宏文件…" : "还没有宏文件，点击添加或拖入 .java / .jar 文件",
                width / 2, 91, 0xFF94A3B8);
        String running = manager() == null ? "服务不可用" : manager().state()
                + (manager().active() ? " · " + manager().activeFile() : "");
        text(graphics, "运行状态：" + running, 12, height - 98, width - 24, 0xFF80D8A0);
        var logs = manager() == null ? List.<String>of() : manager().logs();
        String latest = !message.isEmpty() ? message : logs.isEmpty() ? "可添加多个文件；同一时间运行一个宏" : logs.get(logs.size() - 1);
        text(graphics, latest, 12, height - 83, width - 24, 0xFFCBD5E1);
        text(graphics, minecraft.level == null ? "请先进入世界再启动宏" : "所选：" + (selected == null ? "无" : selected),
                12, height - 67, width - 24, 0xFF94A3B8);
    }
    private final class FileList extends ObjectSelectionList<Row> {
        FileList(int listHeight) {
            super(MacroScreen.this.minecraft, MacroScreen.this.width - 24, listHeight, 80, 24);
            setX(12);
        }
        @Override public int getRowWidth() { return MacroScreen.this.width - 40; }
        @Override public void setSelected(Row row) {
            super.setSelected(row);
            if (row != null) {
                if (!row.file.name().equals(selected)) MacroScreen.this.message = "";
                selected = row.file.name();
            }
        }
    }
    private final class Row extends ObjectSelectionList.Entry<Row> {
        private final MacroFiles.Entry file;
        Row(MacroFiles.Entry file) { this.file = file; }
        @Override public Component getNarration() { return Component.literal(file.name() + " " + file.type()); }
        @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (event.button() != 0) return false;
            list.setSelected(this); return true;
        }
        @Override public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float delta) {
            int x = getContentX(), y = getContentY() + 3, w = width - 44;
            text(graphics, file.name(), x, y, w / 2 - 6, 0xFFFFFFFF);
            text(graphics, file.type(), x + w / 2, y, w / 5 - 4, 0xFFCBD5E1);
            String size = file.size() < 1024 ? file.size() + " B" : String.format(java.util.Locale.ROOT, "%.0f KB", file.size() / 1024.0);
            text(graphics, size, x + w * 7 / 10, y, w * 13 / 100 - 4, 0xFFCBD5E1);
            text(graphics, manager().fileState(file.name()), x + w * 83 / 100, y, w * 17 / 100, 0xFF80D8A0);
        }
    }
}
