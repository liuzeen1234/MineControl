package dev.minecontrol.mixin;

import dev.minecontrol.MineControlClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep the stop hint readable even when a macro opens a GUI or is run outside a world. */
@Mixin(Screen.class)
public abstract class MacroOverlayMixin {
    @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("TAIL"))
    private void minecontrol$showStopHint(GuiGraphicsExtractor graphics, int x, int y, float delta, CallbackInfo ci) {
        MineControlClient.drawMacroStatus(graphics);
    }
}
