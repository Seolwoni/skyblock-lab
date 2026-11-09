package dev.marceline.features.impl.visual

import dev.marceline.config.types.SliderSetting
import dev.marceline.config.types.ToggleSetting
import dev.marceline.features.Feature
import dev.marceline.utils.ColorUtils.withAlpha
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Color

/**
 * @see dev.marceline.mixin.MixinGui.onRenderHudPre
 * @see dev.marceline.mixin.MixinGui.onRenderHudPost
 */
object DarkMode: Feature("Darkens the screen") {
    private val opacity by SliderSetting("Opacity", 25, 1, 80, 1, "%").withDescription("The strength of the dark tint.")

    @JvmStatic
    val tintHud by ToggleSetting("Tint HUD").withDescription("Should the dark tint also apply to HUD elements?")

    @JvmStatic
    fun drawOverlay(ctx: GuiGraphicsExtractor) {
        if (enabled) ctx.fill(
            0, 0,
            mc.window.guiScaledWidth,
            mc.window.guiScaledHeight,
            Color.BLACK.withAlpha(opacity.value / 100f).rgb
        )
    }
}