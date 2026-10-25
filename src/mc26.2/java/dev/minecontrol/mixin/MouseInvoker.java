package dev.minecontrol.mixin;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(MouseHandler.class)
public interface MouseInvoker {
    @Invoker("onButton") void minecontrol$button(long window, net.minecraft.client.input.MouseButtonInfo button, int action);
    @Invoker("onMove") void minecontrol$cursor(long window, double x, double y);
}
