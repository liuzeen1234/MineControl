package dev.minecontrol.mixin;

import dev.minecontrol.MineControlClient;
import dev.minecontrol.SimulatedInput;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.PreeditEvent;
import com.mojang.blaze3d.platform.InputConstants;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public abstract class MacroKeyboardMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void minecontrol$captureKey(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (MineControlClient.capturePhysicalInput(window) && !SimulatedInput.active()) {
            if (action == InputConstants.PRESS) {
                MineControlClient.handleStopKey(event);
                MineControlClient.handleOpenMacrosKey(event);
            }
            ci.cancel();
        }
    }
    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void minecontrol$captureText(long window, CharacterEvent event, CallbackInfo ci) {
        if (MineControlClient.capturePhysicalInput(window) && !SimulatedInput.active()) ci.cancel();
    }
    @Inject(method = "textInput", at = @At("HEAD"), cancellable = true)
    private void minecontrol$captureString(long window, String text, CallbackInfo ci) {
        if (MineControlClient.capturePhysicalInput(window) && !SimulatedInput.active()) ci.cancel();
    }
    @Inject(method = "textEditing", at = @At("HEAD"), cancellable = true)
    private void minecontrol$capturePreedit(long window, PreeditEvent event, CallbackInfo ci) {
        if (MineControlClient.capturePhysicalInput(window) && !SimulatedInput.active()) ci.cancel();
    }
}
