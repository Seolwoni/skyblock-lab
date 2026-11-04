package com.github.noamm9.features.impl.visual

import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.features.Feature
import com.github.noamm9.ui.clickgui.components.settings.Style
import com.github.noamm9.utils.ColorUtils.withAlpha
import com.github.noamm9.utils.render.Render2D.drawVerticalGradient
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Color

/**
 * The player list (Tab) in the Riftwalk look: the vanilla grey boxes become rounded dark-violet glass panels
 * with a soft shadow, a hairline edge and an accent line, and each player's slot a faint violet chip.
 * Hooked into the vanilla fills (MixinPlayerTabOverlay), so everything the server puts in the list stays as it is.
 */
object TabStyle: Feature("Draws the Tab player list in the Riftwalk violet glass look.", toggled = true) {
    private val opacity by SliderSetting("Panel Opacity", 82, 30, 100, 2, "%")
    private val slotTint by SliderSetting("Slot Tint", 14, 0, 40, 1, "%").withDescription("How visible each player's slot chip is")

    private const val VANILLA_PANEL = Int.MIN_VALUE          // 0x80000000
    private const val VANILLA_SLOT = 553648127               // 0x20FFFFFF

    /** Called for every fill in the Tab list. True when we drew it ourselves. */
    @JvmStatic
    fun fill(ctx: GuiGraphicsExtractor, x1: Int, y1: Int, x2: Int, y2: Int, color: Int): Boolean {
        if (! enabled) return false
        val x = x1.toFloat()
        val y = y1.toFloat()
        val w = (x2 - x1).toFloat()
        val h = (y2 - y1).toFloat()
        if (w <= 0f || h <= 0f) return true
        when (color) {
            VANILLA_PANEL -> {
                val a = (opacity.value / 100f * 255).toInt()
                Style.drawShadow(ctx, x - 2f, y - 2f, w + 4f, h + 4f, 5f, 0.5f)
                Style.roundRect(ctx, x - 2.5f, y - 2.5f, w + 5f, h + 5f, 5.5f, Color(255, 255, 255, 18))
                Style.roundRect(ctx, x - 2f, y - 2f, w + 4f, h + 4f, 5f, Color(16, 11, 26, a))
                ctx.drawVerticalGradient(x, y - 1.5f, w, minOf(8f, h / 2f), Color(255, 255, 255, 12), Color(255, 255, 255, 0))
                Style.roundRect(ctx, x + 6f, y - 2f, w - 12f, 0.75f, 0.4f, Style.mix(Style.accentColor, Style.accent2, 0.5f).withAlpha(150))
                return true
            }
            VANILLA_SLOT -> {
                Style.roundRect(ctx, x, y, w, h, 1.5f, Color(167, 107, 250, (slotTint.value / 100f * 255).toInt()))
                return true
            }
        }
        return false
    }
}
