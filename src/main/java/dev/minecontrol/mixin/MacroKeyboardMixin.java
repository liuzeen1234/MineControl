package dev.minecontrol.mixin;

import dev.minecontrol.MineControlClient;
import dev.minecontrol.SimulatedInput;
import net.minecraft.client.Keyboard;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Keyboard.class)
public abstract class MacroKeyboardMixin {
    @Inject(method = "onKey", at = @At("HEAD"), cancellable = true)
    private void captureKey(long window, int key, int scanCode, int action, int modifiers, CallbackInfo ci) {
        if (SimulatedInput.active()) return;
        if (action == GLFW.GLFW_PRESS) {
            MineControlClient.handleStopKey(key, scanCode);
            if (MineControlClient.handleOpenMacrosKey(key, scanCode)) { ci.cancel(); return; }
        }
        if (MineControlClient.capturePhysicalInput(window)) {
            ci.cancel();
        }
    }
    @Inject(method = "onChar", at = @At("HEAD"), cancellable = true)
    private void captureText(long window, int codePoint, int modifiers, CallbackInfo ci) {
        if (MineControlClient.capturePhysicalInput(window) && !SimulatedInput.active()) ci.cancel();
    }
}
