package dev.minecontrol.mixin;
import dev.minecontrol.MineControlClient;
import com.mojang.blaze3d.platform.InputConstants;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(InputConstants.class)
public class InputUtilMixin {
    @Inject(method = "isKeyDown", at = @At("HEAD"), cancellable = true)
    private static void minecontrol$held(com.mojang.blaze3d.platform.Window window, int key, CallbackInfoReturnable<Boolean> cir) {
        if (MineControlClient.isHeld(window.handle(), key)) cir.setReturnValue(true);
    }
}
