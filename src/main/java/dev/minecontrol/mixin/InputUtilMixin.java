package dev.minecontrol.mixin;
import dev.minecontrol.MineControlClient;
import net.minecraft.client.util.InputUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(InputUtil.class)
public class InputUtilMixin {
    @Inject(method = "isKeyPressed", at = @At("HEAD"), cancellable = true)
    private static void minecontrol$held(long window, int key, CallbackInfoReturnable<Boolean> cir) {
        if (MineControlClient.isHeld(window, key)) cir.setReturnValue(true);
    }
}
