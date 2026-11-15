package dev.minecontrol.mixin;

import dev.minecontrol.MineControlClient;
import dev.minecontrol.MouseCaptureAccess;
import dev.minecontrol.SimulatedInput;
import net.minecraft.client.Mouse;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
public abstract class MacroMouseMixin implements MouseCaptureAccess {
    @Shadow private boolean leftButtonClicked;
    @Shadow private boolean middleButtonClicked;
    @Shadow private boolean rightButtonClicked;
    @Shadow private double cursorDeltaX;
    @Shadow private double cursorDeltaY;
    @Shadow private boolean hasResolutionChanged;
    @Override public void minecontrol$clearInputState() {
        leftButtonClicked = middleButtonClicked = rightButtonClicked = false;
        cursorDeltaX = cursorDeltaY = 0;
        hasResolutionChanged = true;
    }
    @Inject(method = "lockCursor", at = @At("RETURN"))
    private void releaseCursor(CallbackInfo ci) { MineControlClient.releaseSystemCursor(); }
    @Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
    private void captureButton(long window, int button, int action, int modifiers, CallbackInfo ci) {
        if (SimulatedInput.active()) return;
        if (action == GLFW.GLFW_PRESS) MineControlClient.handleStopMouse(button);
        if (MineControlClient.capturePhysicalInput(window)) ci.cancel();
    }
    @Inject(method = "onCursorPos", at = @At("HEAD"), cancellable = true)
    private void captureMotion(long window, double x, double y, CallbackInfo ci) {
        if (MineControlClient.capturePhysicalInput(window) && !SimulatedInput.active()) ci.cancel();
    }
    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
    private void captureScroll(long window, double x, double y, CallbackInfo ci) {
        if (MineControlClient.capturePhysicalInput(window) && !SimulatedInput.active()) ci.cancel();
    }
    @Inject(method = "onFilesDropped", at = @At("HEAD"), cancellable = true)
    private void captureDrop(long window, java.util.List<java.nio.file.Path> paths, CallbackInfo ci) {
        if (MineControlClient.capturePhysicalInput(window) && !SimulatedInput.active()) ci.cancel();
    }
    @Inject(method = "updateMouse", at = @At("HEAD"))
    private void clearMotion(CallbackInfo ci) {
        if (MineControlClient.inputCaptured()) cursorDeltaX = cursorDeltaY = 0;
    }
}
