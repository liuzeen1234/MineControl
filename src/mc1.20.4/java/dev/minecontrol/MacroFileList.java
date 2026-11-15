package dev.minecontrol;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
/** Small adapter for the list constructor changed in 1.20.4. */
abstract class MacroFileList<E extends AlwaysSelectedEntryListWidget.Entry<E>> extends AlwaysSelectedEntryListWidget<E> {
    MacroFileList(MinecraftClient client, int width, int height, int listHeight) {
        super(client, width, listHeight, 80, 24);
        setX(12);
    }
}
