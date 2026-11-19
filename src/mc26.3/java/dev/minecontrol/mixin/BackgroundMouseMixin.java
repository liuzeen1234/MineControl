package dev.minecontrol.mixin;

import dev.minecontrol.MineControlClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import com.mojang.blaze3d.platform.InputConstants;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Background focus spoofing must not turn desktop pointer movement into camera movement. */
@Mixin(MouseHandler.class)
public abstract class BackgroundMouseMixin {
    @Shadow private double accumulatedDX;
    @Shadow private double accumulatedDY;
    @Inject(method = "turnPlayer", at = @At("HEAD"))
    private void minecontrol$ignoreDesktopMotion(double elapsed, CallbackInfo ci) {
        var client = Minecraft.getInstance();
        if (MineControlClient.backgroundEnabled() && client.gui.screen() == null
                && !client.getWindow().isFocused()) {
            accumulatedDX = 0; accumulatedDY = 0;
        }
    }
}
