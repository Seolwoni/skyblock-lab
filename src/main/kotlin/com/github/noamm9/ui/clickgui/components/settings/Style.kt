package com.github.noamm9.ui.clickgui.components.settings

import com.github.noamm9.features.impl.dev.ClickGui
import com.github.noamm9.utils.ColorUtils.withAlpha
import com.github.noamm9.utils.NumbersUtils.div
import com.github.noamm9.utils.NumbersUtils.minus
import com.github.noamm9.utils.NumbersUtils.plus
import com.github.noamm9.utils.SoundUtils
import com.github.noamm9.utils.render.Render2D.drawRect
import com.github.noamm9.utils.render.Render2D.drawString
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.sounds.SoundEvents
import java.awt.Color

object Style {
    val accentColor get() = ClickGui.accentColor.value
    val accentColorTrans get() = accentColor.withAlpha(120)
    val settingBackgroundColor = Color(10, 10, 10, 100)
    val sliderBackgroundColor = Color(40, 40, 40, 200)

    fun drawBackground(ctx: GuiGraphicsExtractor, x: Number, y: Number, w: Number, h: Number) {
        ctx.drawRect(x, y, w, h, settingBackgroundColor)
    }

    fun drawHoverBar(ctx: GuiGraphicsExtractor, x: Number, y: Number, height: Number, anim: Float) {
        if (anim <= 0.01f) return
        val barH = (height - 6f) * anim
        val barY = y + (height / 2f) - (barH / 2f)
        ctx.drawRect(x, barY, 1.5f, barH, accentColor.withAlpha((200 * anim).toInt()))
    }

    fun drawNudgedText(ctx: GuiGraphicsExtractor, text: String, x: Float, y: Float, anim: Float, color: Color = Color.WHITE) {
        val xOffset = 2f * anim
        ctx.drawString(text, x + xOffset, y, color)
    }

    fun drawSlider(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, progress: Float, color: Color) {
        val kSize = 6f
        val h = 3f
        roundRect(ctx, x, y, w, h, 1.5f, sliderBackgroundColor)
        if (progress > 0.01f) roundRect(ctx, x, y, w * progress, h, 1.5f, color)
        roundRect(ctx, x + (w * progress) - (kSize / 2f), y + (h / 2f) - (kSize / 2f), kSize, kSize, 3f, Color(240, 236, 255))
    }

    /** A filled rectangle with rounded corners (radius in GUI pixels, drawn as stacked strips). */
    fun roundRect(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, radius: Float, color: Color) {
        val r = radius.coerceAtMost(minOf(w, h) / 2f).coerceAtLeast(0f)
        if (r < 0.75f) return ctx.drawRect(x, y, w, h, color)
        ctx.drawRect(x, y + r, w, h - 2 * r, color)
        val steps = kotlin.math.ceil(r * 2).toInt().coerceAtLeast(2)
        val strip = r / steps
        for (i in 0 until steps) {
            val dy = r - (i + 0.5f) * strip
            val inset = r - kotlin.math.sqrt((r * r - dy * dy).coerceAtLeast(0f))
            ctx.drawRect(x + inset, y + i * strip, w - 2 * inset, strip, color)
            ctx.drawRect(x + inset, y + h - (i + 1) * strip, w - 2 * inset, strip, color)
        }
    }

    /** Pill-shaped on/off switch. [progress] 0 = off, 1 = on (animated by the caller). */
    fun drawSwitch(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, progress: Float) {
        val off = Color(58, 54, 70)
        roundRect(ctx, x, y, w, h, h / 2f, off.lerpTo(accentColor, progress))
        val knob = h - 2f
        val kx = x + 1f + (w - knob - 2f) * progress
        roundRect(ctx, kx, y + 1f, knob, knob, knob / 2f, Color(143, 139, 163).lerpTo(Color.WHITE, progress))
    }

    private fun Color.lerpTo(other: Color, t: Float): Color {
        val k = t.coerceIn(0f, 1f)
        fun c(a: Int, b: Int) = (a + (b - a) * k).toInt().coerceIn(0, 255)
        return Color(c(red, other.red), c(green, other.green), c(blue, other.blue), c(alpha, other.alpha))
    }

    fun drawRangeSlider(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, start: Float, end: Float, color: Color) {
        val kSize = 4f
        val h = 2.5f
        ctx.drawRect(x, y, w, h, sliderBackgroundColor)
        ctx.drawRect(x + (w * start), y, w * (end - start), h, color)
        ctx.drawRect(x + (w * start) - (kSize / 2f), y + (h / 2f) - (kSize / 2f), kSize, kSize)
        ctx.drawRect(x + (w * end) - (kSize / 2f), y + (h / 2f) - (kSize / 2f), kSize, kSize)
    }

    fun playClickSound(pitch: Float) = SoundUtils.playSound(SoundEvents.UI_BUTTON_CLICK, 0.25f, pitch)
}