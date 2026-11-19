package dev.minecontrol;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.*;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import java.nio.file.Path;
import java.util.List;

/** A file browser for the external Java macros in this instance. */
public final class MacroScreen extends Screen {
    private final Screen parent;
    private ButtonWidget start, stop, delete, add, refresh;
    private boolean refreshing;
    private FileList list;
    private List<MacroFiles.Entry> displayed = List.of();
    private String selected;
    private String message = "";
    private int ticks;
    private double savedScroll;
    @Override public void removed() {
        if (list != null) savedScroll = list.getScrollAmount();
        super.removed();
    }
    public MacroScreen(Screen parent) {
        super(Text.translatable("minecontrol.macros.title"));
        this.parent = parent;
    }
    private JavaMacroManager manager() { return MineControlClient.javaMacros(); }
    @Override protected void init() {
        addDrawableChild(ButtonWidget.builder(Text.translatable("minecontrol.settings.open"), button ->
                client.setScreen(new SettingsScreen(this)))
                .dimensions(width - 64, 4, 52, 20).build());
        int gap = 4, buttonWidth = (width - 24 - gap * 3) / 4;
        add = addDrawableChild(ButtonWidget.builder(Text.translatable("minecontrol.files.add"), button ->
                client.setScreen(new AddMacroScreen(this)))
                .dimensions(12, 28, buttonWidth, 20).build());
        delete = addDrawableChild(ButtonWidget.builder(Text.translatable("minecontrol.files.delete"), button -> {
            if (selected == null || manager() == null) return;
            String name = selected;
            manager().deleteFile(name).whenComplete((result, error) -> client.execute(() -> {
                message = error == null ? UiText.get("minecontrol.files.deleted") : errorMessage(error);
                reload();
            }));
        }).dimensions(12 + buttonWidth + gap, 28, buttonWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("minecontrol.files.folder"), button -> {
            if (manager() != null) Util.getOperatingSystem().open(manager().directory().toFile());
        }).dimensions(12 + 2 * (buttonWidth + gap), 28, buttonWidth, 20).build());
        refresh = addDrawableChild(ButtonWidget.builder(Text.translatable("minecontrol.files.refresh"), button -> refreshFiles())
                .dimensions(12 + 3 * (buttonWidth + gap), 28, buttonWidth, 20).build());
        list = addDrawableChild(new FileList(Math.max(30, height - 184)));
        start = addDrawableChild(ButtonWidget.builder(Text.translatable("minecontrol.macros.start"), button -> {
            if (manager() != null && selected != null) {
                manager().resume();
                if (manager().start(selected)) client.setScreen(null);
            }
        }).dimensions(12, height - 50, (width - 28) / 2, 20).build());
        stop = addDrawableChild(ButtonWidget.builder(Text.translatable("minecontrol.macros.stop"), button -> {
            if (manager() != null && selected != null) manager().stop(selected);
        }).dimensions(16 + (width - 28) / 2, height - 50, (width - 28) / 2, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("minecontrol.back"), button -> close())
                .dimensions(width / 2 - 50, height - 26, 100, 20).build());
        displayed = List.of();
        reload();
        list.setScrollAmount(savedScroll);
        updateButtonWidgets();
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
        double scroll = list.getScrollAmount();
        list.setFiles(entries.stream().map(Row::new).toList());
        for (Row row : list.children()) if (row.file.name().equals(selected)) list.setSelected(row);
        list.setScrollAmount(scroll);
    }
    private void updateButtonWidgets() {
        var manager = manager();
        boolean available = manager != null && !manager.loading();
        start.active = available && selected != null && !manager.running(selected) && client.world != null;
        stop.active = manager != null && selected != null && manager.running(selected);
        delete.active = available && selected != null && !manager.running(selected);
        add.active = available;
        refresh.active = available && !refreshing;
        refresh.setMessage(Text.literal(refreshing ? UiText.get("minecontrol.files.refreshing") : UiText.get("minecontrol.files.refresh")));
    }
    private void refreshFiles() {
        var manager = manager();
        if (manager == null || manager.loading() || refreshing) return;
        refreshing = true;
        message = UiText.get("minecontrol.files.reading");
        updateButtonWidgets();
        manager.refresh().whenComplete((result, error) -> client.execute(() -> {
            refreshing = false;
            reload();
            message = error == null ? UiText.format("minecontrol.files.refreshed", manager.entries().size()) : UiText.get("minecontrol.files.refresh_failed") + errorMessage(error);
            updateButtonWidgets();
        }));
    }
    @Override public void tick() {
        reload(); updateButtonWidgets();
        if (++ticks % 40 == 0 && manager() != null && !manager().loading()) manager().refresh();
    }
    public void importFiles(List<Path> paths) {
        if (manager() == null) return;
        message = UiText.get("minecontrol.files.adding");
        manager().addFiles(paths).whenComplete((result, error) -> client.execute(() -> {
            message = error == null ? UiText.get("minecontrol.files.added") : errorMessage(error);
            reload();
        }));
    }
    @Override public void filesDragged(List<Path> paths) { importFiles(paths); }
    static String errorMessage(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return UiText.status(error.getMessage() == null ? error.toString() : error.getMessage());
    }
    @Override public boolean shouldPause() { return false; }
    @Override public void close() { client.setScreen(parent); }
    private void text(DrawContext graphics, String text, int x, int y, int maxWidth, int color) {
        graphics.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(text, Math.max(0, maxWidth)), x, y, color);
    }
    @Override public void render(DrawContext graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xEE101820);
        super.render(graphics, mouseX, mouseY, delta);
        graphics.drawCenteredTextWithShadow(textRenderer, title, width / 2, 10, 0xFFFFFFFF);
        String directory = manager() == null ? UiText.get("minecontrol.service.error") : UiText.get("minecontrol.files.directory");
        text(graphics, directory, 12, 53, width - 24, 0xFF94A3B8);
        graphics.fill(12, 66, width - 12, 80, 0xCC243447);
        int rowWidth = width - 44;
        text(graphics, UiText.get("minecontrol.files.name"), 20, 69, rowWidth / 2, 0xFFFFFFFF);
        text(graphics, UiText.get("minecontrol.files.type"), 20 + rowWidth / 2, 69, rowWidth / 5, 0xFFFFFFFF);
        text(graphics, UiText.get("minecontrol.files.size"), 20 + rowWidth * 7 / 10, 69, rowWidth / 8, 0xFFFFFFFF);
        text(graphics, UiText.get("minecontrol.files.state"), 20 + rowWidth * 83 / 100, 69, rowWidth / 6, 0xFFFFFFFF);
        if (displayed.isEmpty()) graphics.drawCenteredTextWithShadow(textRenderer,
                manager() != null && manager().loading() ? UiText.get("minecontrol.files.loading") : UiText.get("minecontrol.files.empty"),
                width / 2, 91, 0xFF94A3B8);
        String running = manager() == null ? UiText.get("minecontrol.service.unavailable") : UiText.status(manager().state());
        text(graphics, UiText.get("minecontrol.macros.status") + running, 12, height - 98, width - 24, 0xFF80D8A0);
        var logs = manager() == null ? List.<String>of() : manager().logs();
        String latest = !message.isEmpty() ? message : logs.isEmpty() ? UiText.get("minecontrol.macros.roles") : UiText.status(logs.get(logs.size() - 1));
        text(graphics, latest, 12, height - 83, width - 24, 0xFFCBD5E1);
        text(graphics, client.world == null ? UiText.get("minecontrol.macros.world_required")
                        : UiText.get("minecontrol.macros.stop_key") + MineControlClient.stopKeyLabel() + UiText.get("minecontrol.macros.stop_all_suffix")
                                + (MineControlClient.blockPhysicalInputDuringMacros() ? UiText.get("minecontrol.input.blocked") : UiText.get("minecontrol.input.allowed")),
                12, height - 67, width - 24, 0xFF94A3B8);
    }
    private final class FileList extends MacroFileList<Row> {
        void setFiles(java.util.Collection<Row> rows) { replaceEntries(rows); }
        FileList(int listHeight) {
            super(MacroScreen.this.client, MacroScreen.this.width - 24, MacroScreen.this.height, listHeight);
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
    private final class Row extends AlwaysSelectedEntryListWidget.Entry<Row> {
        private final MacroFiles.Entry file;
        Row(MacroFiles.Entry file) { this.file = file; }
        @Override public Text getNarration() { return Text.literal(file.name() + " " + UiText.status(file.type())); }
        @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != 0) return false;
            list.setSelected(this); return true;
        }
        @Override public void render(DrawContext graphics, int index, int top, int left, int rowWidth, int rowHeight, int mouseX, int mouseY, boolean hovered, float delta) {
            int x = left, y = top + 3, w = width - 44;
            text(graphics, file.name(), x, y, w / 2 - 6, 0xFFFFFFFF);
            text(graphics, UiText.status(file.type()), x + w / 2, y, w / 5 - 4, 0xFFCBD5E1);
            String size = file.size() < 1024 ? file.size() + " B" : String.format(java.util.Locale.ROOT, "%.0f KB", file.size() / 1024.0);
            text(graphics, size, x + w * 7 / 10, y, w * 13 / 100 - 4, 0xFFCBD5E1);
            text(graphics, UiText.status(manager().fileState(file.name())), x + w * 83 / 100, y, w * 17 / 100, 0xFF80D8A0);
        }
    }
}
