package dev.minecontrol.mixin;
import dev.minecontrol.MineControlClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(Minecraft.class)
public abstract class ClientFocusMixin {
    @Inject(method = "isWindowActive", at = @At("HEAD"), cancellable = true)
    private void minecontrol$background(CallbackInfoReturnable<Boolean> cir) {
        if (MineControlClient.backgroundEnabled()) cir.setReturnValue(true);
    }
}
