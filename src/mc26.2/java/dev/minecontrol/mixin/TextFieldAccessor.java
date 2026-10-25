package dev.minecontrol.mixin;
import net.minecraft.client.gui.components.EditBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(EditBox.class)
public interface TextFieldAccessor {
    @Accessor("isEditable") boolean minecontrol$editable();
}
