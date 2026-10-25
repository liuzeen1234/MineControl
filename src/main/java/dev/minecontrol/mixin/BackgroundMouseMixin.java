package dev.minecontrol.mixin;

import dev.minecontrol.MineControlClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Background focus spoofing must not turn desktop pointer movement into camera movement. */
@Mixin(Mouse.class)
public abstract class BackgroundMouseMixin {
    @Shadow private double cursorDeltaX;
    @Shadow private double cursorDeltaY;
    @Inject(method = "updateMouse", at = @At("HEAD"))
    private void minecontrol$ignoreDesktopMotion(CallbackInfo ci) {
        var client = MinecraftClient.getInstance();
        if (MineControlClient.backgroundEnabled() && client.currentScreen == null
                && GLFW.glfwGetWindowAttrib(client.getWindow().getHandle(), GLFW.GLFW_FOCUSED) == GLFW.GLFW_FALSE) {
            cursorDeltaX = 0; cursorDeltaY = 0;
        }
    }
}
