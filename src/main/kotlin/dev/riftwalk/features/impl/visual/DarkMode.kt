package dev.riftwalk.features.impl.visual

import dev.riftwalk.config.types.SliderSetting
import dev.riftwalk.config.types.ToggleSetting
import dev.riftwalk.features.Feature
import dev.riftwalk.utils.ColorUtils.withAlpha
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Color

/**
 * @see dev.riftwalk.mixin.MixinGui.onRenderHudPre
 * @see dev.riftwalk.mixin.MixinGui.onRenderHudPost
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