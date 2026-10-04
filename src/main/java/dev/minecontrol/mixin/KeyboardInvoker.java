package dev.minecontrol.mixin;
import net.minecraft.client.Keyboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(Keyboard.class)
public interface KeyboardInvoker {
    @Invoker("onKey") void minecontrol$key(long window, int key, int scanCode, int action, int mods);
}
