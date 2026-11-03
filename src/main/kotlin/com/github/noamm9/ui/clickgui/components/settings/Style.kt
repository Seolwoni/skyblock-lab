package com.github.noamm9.ui.clickgui.components.settings

import com.github.noamm9.features.impl.dev.ClickGui
import com.github.noamm9.utils.ColorUtils.withAlpha
import com.github.noamm9.utils.NumbersUtils.div
import com.github.noamm9.utils.NumbersUtils.minus
import com.github.noamm9.utils.NumbersUtils.plus
import com.github.noamm9.utils.SoundUtils
import com.github.noamm9.utils.render.Render2D.drawHorizontalGradient
import com.github.noamm9.utils.render.Render2D.drawRect
import com.github.noamm9.utils.render.Render2D.drawString
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.sounds.SoundEvents
import java.awt.Color

object Style {
    val accentColor get() = ClickGui.accentColor.value
    /** Void Rift: the second colour of every gradient (purple -> pink). */
    val accent2 = Color(236, 72, 153)
    val accentColorTrans get() = accentColor.withAlpha(120)
    val settingBackgroundColor = Color(12, 8, 20, 110)
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
        roundRect(ctx, x, y, w, h, 1.5f, Color(42, 31, 61))
        if (progress > 0.01f) gradientPill(ctx, x, y, w * progress, h, color, mix(color, accent2, progress))
        roundRect(ctx, x + (w * progress) - (kSize / 2f) - 1f, y + (h / 2f) - (kSize / 2f) - 1f, kSize + 2f, kSize + 2f, 4f, mix(color, accent2, progress).withAlpha(70))
        roundRect(ctx, x + (w * progress) - (kSize / 2f), y + (h / 2f) - (kSize / 2f), kSize, kSize, 3f, Color(245, 240, 255))
    }

    /** Rounded value badge (slider numbers, key binds): purple -> pink, an outlined field while editing. */
    fun valuePill(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, editing: Boolean, hover: Float = 0f) {
        val r = h / 2f
        if (editing) {
            roundRect(ctx, x - 1f, y - 1f, w + 2f, h + 2f, r + 1f, accentColor)
            roundRect(ctx, x, y, w, h, r, Color(11, 8, 18))
            return
        }
        val a = (150 + 70 * hover).toInt().coerceIn(0, 255)
        gradientPill(ctx, x, y, w, h, accentColor.withAlpha(a), accent2.withAlpha(a))
    }

    /** Width of [s] with every digit as wide as "0", so numbers don't wobble when they change. */
    fun tabWidth(s: String): Float {
        val zero = with(com.github.noamm9.utils.render.RenderHelper) { "0".width() }.toFloat()
        var w = 0f
        for (ch in s) w += if (ch.isDigit()) zero else with(com.github.noamm9.utils.render.RenderHelper) { ch.toString().width() }.toFloat()
        return w
    }

    /** Draws [s] with fixed-width digits (each centred in a "0"-wide cell). */
    fun drawTabular(ctx: GuiGraphicsExtractor, s: String, x: Float, y: Float, color: Color, shadow: Boolean = false) {
        val zero = with(com.github.noamm9.utils.render.RenderHelper) { "0".width() }.toFloat()
        var cx = x
        for (ch in s) {
            val cw = with(com.github.noamm9.utils.render.RenderHelper) { ch.toString().width() }.toFloat()
            if (ch.isDigit()) {
                ctx.drawString(ch.toString(), cx + (zero - cw) / 2f, y, color, shadow = shadow)
                cx += zero
            }
            else {
                ctx.drawString(ch.toString(), cx, y, color, shadow = shadow)
                cx += cw
            }
        }
    }

    /** Linear mix of two colours, alpha included. */
    fun mix(a: Color, b: Color, t: Float): Color = a.lerpTo(b, t)

    /** Pill with a left-to-right gradient (rounded ends take the end colours). */
    fun gradientPill(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, from: Color, to: Color) {
        val r = h / 2f
        if (w <= h) return roundRect(ctx, x, y, w, h, r, from)
        if (ClickGui.shaderCorners.value && com.github.noamm9.utils.render.ShaderUI.available) {
            // half caps that meet the strip edge to edge, so see-through pills have no darker bands
            com.github.noamm9.utils.render.ShaderUI.halfCap(ctx, x, y, r, from, left = true)
            com.github.noamm9.utils.render.ShaderUI.halfCap(ctx, x + w - r, y, r, to, left = false)
            ctx.drawHorizontalGradient(x + r, y, w - h, h, from, to)
            return
        }
        roundRect(ctx, x, y, h, h, r, from)
        roundRect(ctx, x + w - h, y, h, h, r, to)
        ctx.drawHorizontalGradient(x + r, y, w - h, h, from, to)
    }

    /** Soft glow spreading to the right of a vertical accent strip. */
    fun drawGlow(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, color: Color, strength: Float = 1f) {
        if (strength <= 0.01f) return
        ctx.drawHorizontalGradient(x, y, w, h, color.withAlpha((70 * strength).toInt()), color.withAlpha(0))
    }

    /** Layered soft shadow around a rounded panel. */
    fun drawShadow(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, radius: Float, strength: Float = 1f) {
        // 12 thin layers: with the smooth corners this reads as one soft, slightly lowered shadow
        for (i in 12 downTo 1) {
            val d = i * 0.75f
            val a = (9 * strength * (13 - i) / 12f).toInt().coerceIn(0, 255)
            roundRect(ctx, x - d, y - d + 2.5f, w + d * 2, h + d * 2, radius + d, Color(4, 0, 12, a))
        }
    }

    /** A filled rectangle with rounded corners (radius in GUI pixels, drawn as stacked strips). */
    fun roundRect(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, radius: Float, color: Color) {
        if (ClickGui.shaderCorners.value && com.github.noamm9.utils.render.ShaderUI.roundRect(ctx, x, y, w, h, radius, color)) return
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
        val off = Color(46, 36, 64)
        roundRect(ctx, x, y, w, h, h / 2f, off)
        if (progress > 0.01f) {
            // the purple -> pink fill grows in from the left as it switches on
            val a = (255 * progress).toInt()
            gradientPill(ctx, x, y, w, h, accentColor.withAlpha(a), accent2.withAlpha(a))
            roundRect(ctx, x - 1f, y - 1f, w + 2f, h + 2f, h / 2f + 1f, accentColor.withAlpha((40 * progress).toInt()))
        }
        val knob = h - 2f
        val kx = x + 1f + (w - knob - 2f) * progress
        roundRect(ctx, kx, y + 1f, knob, knob, knob / 2f, Color(138, 127, 163).lerpTo(Color.WHITE, progress))
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

    /** Riftwalk's own "du-geok" keyboard clack (assets/noammaddons/sounds/click.ogg). */
    private val riftClick by lazy {
        net.minecraft.sounds.SoundEvent.createVariableRangeEvent(net.minecraft.resources.Identifier.fromNamespaceAndPath(com.github.noamm9.NoammAddons.MOD_ID, "ui.click"))
    }

    fun playClickSound(pitch: Float) =
        if (ClickGui.riftClickSound.value) SoundUtils.playSound(riftClick, 0.55f, pitch)
        else SoundUtils.playSound(SoundEvents.UI_BUTTON_CLICK, 0.25f, pitch)
}