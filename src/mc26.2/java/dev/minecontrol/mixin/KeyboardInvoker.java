package dev.minecontrol.mixin;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(KeyboardHandler.class)
public interface KeyboardInvoker {
    @Invoker("keyPress") void minecontrol$key(long window, int action, net.minecraft.client.input.KeyEvent event);
}
