package dev.marceline.mixin;

import dev.marceline.ui.menu.LoadingLook;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The loading screen in the Marceline look (see LoadingLook). The vanilla background colour becomes our dark wine,
 * so the screen clear and the vanilla fill match, and our layer is drawn over the vanilla logo with the same fade.
 */
@Mixin(LoadingOverlay.class)
public abstract class MixinLoadingOverlay {
    @Shadow private float currentProgress;
    @Shadow private long fadeOutStart;
    @Shadow private long fadeInStart;
    @Shadow @Final private boolean fadeIn;

    // the IntSupplier behind BRAND_BACKGROUND (red, or black with the dark Mojang background option)
    @Inject(method = "lambda$static$0", at = @At("HEAD"), cancellable = true, require = 0)
    private static void marceline$background(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(LoadingLook.BACKGROUND);
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void marceline$drawLook(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
        long now = Util.getMillis();
        float out = fadeOutStart > -1L ? (now - fadeOutStart) / 1000.0F : -1.0F;
        float in = fadeInStart > -1L ? (now - fadeInStart) / 500.0F : -1.0F;
        float alpha;
        if (out >= 1.0F) alpha = 1.0F - Mth.clamp(out - 1.0F, 0.0F, 1.0F);
        else if (fadeIn) alpha = Mth.clamp(in, 0.15F, 1.0F);
        else alpha = 1.0F;
        LoadingLook.draw(graphics, alpha, currentProgress);
    }
}
