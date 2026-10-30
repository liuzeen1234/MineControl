package dev.minecontrol.mixin;
import dev.minecontrol.MineControlClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Minecraft.class)
public abstract class ClientFocusMixin {
    @Inject(method = "isWindowActive", at = @At("HEAD"), cancellable = true)
    private void minecontrol$background(CallbackInfoReturnable<Boolean> cir) {
        if (MineControlClient.backgroundEnabled() || MineControlClient.inputCaptured()) cir.setReturnValue(true);
    }
    @Inject(method = "pauseIfInactive", at = @At("HEAD"), cancellable = true)
    private void minecontrol$keepMacroRunningOnFocusLoss(CallbackInfo ci) {
        if (MineControlClient.inputCaptured()) ci.cancel();
    }
}
