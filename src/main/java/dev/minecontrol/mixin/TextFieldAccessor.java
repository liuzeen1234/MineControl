package dev.minecontrol.mixin;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(TextFieldWidget.class)
public interface TextFieldAccessor {
    @Accessor("editable") boolean minecontrol$editable();
}
