package dev.minecontrol.mixin;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(Mouse.class)
public interface MouseInvoker {
    @Invoker("onMouseButton") void minecontrol$button(long window, int button, int action, int mods);
    @Invoker("onCursorPos") void minecontrol$cursor(long window, double x, double y);
}
