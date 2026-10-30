package dev.minecontrol.mixin;

import dev.minecontrol.MineControlClient;
import dev.minecontrol.MouseCaptureAccess;
import dev.minecontrol.SimulatedInput;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.util.SmoothDouble;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MacroMouseMixin implements MouseCaptureAccess {
    @Shadow private boolean isLeftPressed;
    @Shadow private boolean isMiddlePressed;
    @Shadow private boolean isRightPressed;
    @Shadow private double accumulatedDX;
    @Shadow private double accumulatedDY;
    @Shadow private double xpos;
    @Shadow private double ypos;
    @Shadow private boolean ignoreFirstMove;
    @Shadow @Final private SmoothDouble smoothTurnX;
    @Shadow @Final private SmoothDouble smoothTurnY;

    @Override public void minecontrol$clearInputState() {
        isLeftPressed = false; isMiddlePressed = false; isRightPressed = false;
        accumulatedDX = 0; accumulatedDY = 0;
        smoothTurnX.reset(); smoothTurnY.reset();
        ignoreFirstMove = true;
    }
    @Inject(method = "grabMouse", at = @At("RETURN"))
    private void minecontrol$keepSystemCursorReleased(CallbackInfo ci) {
        MineControlClient.releaseSystemCursor();
    }
    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void minecontrol$captureButton(long window, MouseButtonInfo button, int action, CallbackInfo ci) {
        if (MineControlClient.capturePhysicalInput(window) && !SimulatedInput.active()) {
            if (action == GLFW.GLFW_PRESS)
                MineControlClient.handleStopMouse(new MouseButtonEvent(xpos, ypos, button));
            ci.cancel();
        }
    }
    @Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
    private void minecontrol$captureMotion(long window, double x, double y, CallbackInfo ci) {
        if (MineControlClient.capturePhysicalInput(window) && !SimulatedInput.active()) ci.cancel();
    }
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void minecontrol$captureScroll(long window, double dx, double dy, CallbackInfo ci) {
        if (MineControlClient.capturePhysicalInput(window) && !SimulatedInput.active()) ci.cancel();
    }
    @Inject(method = "onDrop", at = @At("HEAD"), cancellable = true)
    private void minecontrol$captureDrop(long window, java.util.List<java.nio.file.Path> files, int modifiers, CallbackInfo ci) {
        if (MineControlClient.capturePhysicalInput(window) && !SimulatedInput.active()) ci.cancel();
    }
    @Inject(method = "turnPlayer", at = @At("HEAD"))
    private void minecontrol$clearMotion(double elapsed, CallbackInfo ci) {
        if (MineControlClient.inputCaptured()) {
            accumulatedDX = 0; accumulatedDY = 0;
            smoothTurnX.reset(); smoothTurnY.reset();
        }
    }
}
