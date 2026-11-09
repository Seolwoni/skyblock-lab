package dev.marceline.ui.clickgui.components.settings

import dev.marceline.features.impl.dev.ClickGui
import dev.marceline.utils.ColorUtils.withAlpha
import dev.marceline.utils.NumbersUtils.div
import dev.marceline.utils.NumbersUtils.minus
import dev.marceline.utils.NumbersUtils.plus
import dev.marceline.utils.SoundUtils
import dev.marceline.utils.render.Render2D.drawHorizontalGradient
import dev.marceline.utils.render.Render2D.drawRect
import dev.marceline.utils.render.Render2D.drawString
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.sounds.SoundEvents
import java.awt.Color

object Style {
    val accentColor get() = ClickGui.accentColor.value
    /** Marceline: the second colour of every gradient (wine -> rose). */
    val accent2 = Color(232, 80, 106)
    val accentColorTrans get() = accentColor.withAlpha(120)
    val settingBackgroundColor = Color(13, 9, 10, 110)
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
        roundRect(ctx, x, y, w, h, 1.5f, Color(56, 40, 44))
        if (progress > 0.01f) gradientPill(ctx, x, y, w * progress, h, color, mix(color, accent2, progress))
        roundRect(ctx, x + (w * progress) - (kSize / 2f) - 1f, y + (h / 2f) - (kSize / 2f) - 1f, kSize + 2f, kSize + 2f, 4f, mix(color, accent2, progress).withAlpha(70))
        roundRect(ctx, x + (w * progress) - (kSize / 2f), y + (h / 2f) - (kSize / 2f), kSize, kSize, 3f, Color(246, 238, 228))
    }

    /** Rounded value badge (slider numbers, key binds): wine -> rose, an outlined field while editing. */
    fun valuePill(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, editing: Boolean, hover: Float = 0f) {
        val r = h / 2f
        if (editing) {
            roundRect(ctx, x - 1f, y - 1f, w + 2f, h + 2f, r + 1f, accentColor)
            roundRect(ctx, x, y, w, h, r, Color(11, 9, 10))
            return
        }
        val a = (150 + 70 * hover).toInt().coerceIn(0, 255)
        gradientPill(ctx, x, y, w, h, accentColor.withAlpha(a), accent2.withAlpha(a))
    }

    /** Digit cell: between a "1" and a "0", so changing numbers barely move without wide gaps around a 1. */
    private fun digitCell(): Float {
        val zero = with(dev.marceline.utils.render.RenderHelper) { "0".width() }.toFloat()
        val one = with(dev.marceline.utils.render.RenderHelper) { "1".width() }.toFloat()
        return (zero * 2 + one) / 3f
    }

    /** Width of [s] with every digit in the same cell, so numbers don't wobble when they change. */
    fun tabWidth(s: String): Float {
        val zero = digitCell()
        var w = 0f
        for (ch in s) w += if (ch.isDigit()) zero else with(dev.marceline.utils.render.RenderHelper) { ch.toString().width() }.toFloat()
        return w
    }

    /** Draws [s] with fixed-width digits (each centred in a "0"-wide cell). */
    fun drawTabular(ctx: GuiGraphicsExtractor, s: String, x: Float, y: Float, color: Color, shadow: Boolean = false) {
        val zero = digitCell()
        var cx = x
        for (ch in s) {
            val cw = with(dev.marceline.utils.render.RenderHelper) { ch.toString().width() }.toFloat()
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

    /** Antique gold of the menus (hairlines, subtitles). */
    val gold = Color(201, 164, 106)

    /** A thin gold line that fades out at both ends, like the one under the menu title. */
    fun goldHairline(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, alpha: Float = 1f, thickness: Float = 0.6f) {
        if (w <= 0f || alpha <= 0.01f) return
        val steps = 24
        for (k in 0 until steps) {
            val t = (k + 0.5f) / steps
            val a = (1f - kotlin.math.abs(t * 2f - 1f)).let { it * it }
            ctx.drawRect(x + w * k / steps, y, w / steps + 0.4f, thickness, gold.withAlpha((210 * a * alpha).toInt().coerceIn(0, 255)))
        }
    }

    /**
     * The menu button look: a light rim, dark glass inside it, a wine wash and a gold underline on hover.
     * [hover] 0..1 (animated by the caller), [warn] tints it wine for a state that needs attention.
     */
    fun glassButton(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, hover: Float, warn: Boolean = false, alpha: Float = 1f, radius: Float = 4f) {
        val tint = if (warn) accent2 else accentColor
        val rim = mix(Color(255, 236, 220), tint, if (warn) 1f else hover).withAlpha(((48 + 92 * maxOf(hover, if (warn) 0.6f else 0f)) * alpha).toInt().coerceIn(0, 255))
        roundRect(ctx, x, y, w, h, radius, rim)
        roundRect(ctx, x + 1f, y + 1f, w - 2f, h - 2f, radius - 1f, Color(14, 10, 12, ((150 - 25 * hover) * alpha).toInt().coerceIn(0, 255)))
        val wash = if (warn) 0.55f else hover
        if (wash > 0.02f) roundRect(ctx, x + 1f, y + 1f, w - 2f, h - 2f, radius - 1f, tint.withAlpha((40 * wash * alpha).toInt().coerceIn(0, 255)))
        if (hover > 0.02f) {
            val lw = (w - 2 * radius - 6f).coerceAtLeast(0f) * hover
            ctx.drawRect(x + (w - lw) / 2f, y + h - 2.5f, lw, 0.6f, gold.withAlpha((220 * hover * alpha).toInt().coerceIn(0, 255)))
        }
    }

    /**
     * Minecraft's bright magenta, aqua, yellow and gold codes swapped for the Marceline palette, the rest kept.
     * Returns the colour to draw a run of text in after the code [c] (null = keep the game's own colour).
     */
    fun paletteColor(c: Char): Color? = when (c.lowercaseChar()) {
        'd', '5' -> accent2                      // magenta / purple -> rose
        '6', 'e' -> gold                         // gold / yellow -> antique gold
        'b', '3', '9' -> Color(222, 196, 184)    // aqua / blue -> warm ivory
        'f' -> Color(243, 234, 222)              // white -> ivory
        '7' -> Color(186, 170, 160)              // grey -> warm grey
        '8' -> Color(128, 110, 106)
        'c' -> Color(240, 104, 120)              // red -> soft rose-red
        else -> null
    }

    /**
     * Draws [text] with its colour codes mapped through [paletteColor]; bold carries. [base] is used before the
     * first code and for codes left as they are mapped to the game's colour. Returns the width drawn.
     */
    fun drawPaletteString(ctx: GuiGraphicsExtractor, text: String, x: Float, y: Float, base: Color, width: (String) -> Float, alpha: Int = 255): Float {
        var cx = x
        var color = base
        var bold = false
        var i = 0
        val run = StringBuilder()
        fun flush() {
            if (run.isEmpty()) return
            val s = (if (bold) "§l" else "") + run
            ctx.drawString(s, cx, y, color.withAlpha(alpha * color.alpha / 255), shadow = false)
            cx += width(s)
            run.setLength(0)
        }
        while (i < text.length) {
            val ch = text[i]
            if (ch == '§' && i + 1 < text.length) {
                flush()
                val code = text[i + 1].lowercaseChar()
                when {
                    code == 'l' -> bold = true
                    code == 'r' -> { bold = false; color = base }
                    code in "0123456789abcdef" -> {
                        bold = false
                        color = paletteColor(code) ?: Color(net.minecraft.ChatFormatting.getByCode(code)?.color ?: base.rgb and 0xFFFFFF)
                    }
                }
                i += 2
                continue
            }
            run.append(ch)
            i++
        }
        flush()
        return cx - x
    }

    /** Linear mix of two colours, alpha included. */
    fun mix(a: Color, b: Color, t: Float): Color = a.lerpTo(b, t)

    /** Pill with a left-to-right gradient (rounded ends take the end colours). */
    fun gradientPill(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, from: Color, to: Color) {
        val r = h / 2f
        if (w <= h) return roundRect(ctx, x, y, w, h, r, from)
        if (ClickGui.shaderCorners.value && dev.marceline.utils.render.ShaderUI.available) {
            // half caps that meet the strip edge to edge, so see-through pills have no darker bands
            dev.marceline.utils.render.ShaderUI.halfCap(ctx, x, y, r, from, left = true)
            dev.marceline.utils.render.ShaderUI.halfCap(ctx, x + w - r, y, r, to, left = false)
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
        // 12 thin layers: with the smooth corners this reads as one soft, slightly lowered shadow (3 on phones)
        val layers = if (ClickGui.mobileMode.value) 3 else 12
        for (i in layers downTo 1) {
            val d = i * 0.75f * 12f / layers
            val a = (9 * strength * (13 - i * 12f / layers) / 12f * 12f / layers).toInt().coerceIn(0, 255)
            roundRect(ctx, x - d, y - d + 2.5f, w + d * 2, h + d * 2, radius + d, Color(8, 2, 4, a))
        }
    }

    /** A filled rectangle with rounded corners (radius in GUI pixels, drawn as stacked strips). */
    fun roundRect(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, radius: Float, color: Color) {
        if (ClickGui.shaderCorners.value && dev.marceline.utils.render.ShaderUI.roundRect(ctx, x, y, w, h, radius, color)) return
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
        // off: a lighter track with a hairline edge, so "off" reads clearly on the dark cards
        roundRect(ctx, x - 0.5f, y - 0.5f, w + 1f, h + 1f, h / 2f + 0.5f, Color(201, 164, 106, (90 * (1f - progress)).toInt()))
        roundRect(ctx, x, y, w, h, h / 2f, Color(66, 48, 52))
        if (progress > 0.01f) {
            // the wine -> rose fill grows in from the left as it switches on
            val a = (255 * progress).toInt()
            gradientPill(ctx, x, y, w, h, accentColor.withAlpha(a), accent2.withAlpha(a))
            roundRect(ctx, x - 1f, y - 1f, w + 2f, h + 2f, h / 2f + 1f, accentColor.withAlpha((40 * progress).toInt()))
        }
        val knob = h - 2f
        // mid-switch the knob stretches like a finger dragging it, and settles round at either end
        val stretch = if (progress > 0.02f && progress < 0.98f) kotlin.math.sin(progress * Math.PI).toFloat() * knob * 0.5f else 0f
        val kw = knob + stretch
        val kx = x + 1f + (w - kw - 2f) * progress
        roundRect(ctx, kx, y + 1f, kw, knob, knob / 2f, Color(200, 184, 176).lerpTo(Color.WHITE, progress))
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

    /** Marceline's own "du-geok" keyboard clack (assets/marceline/sounds/click.ogg). */
    private val riftClick by lazy {
        net.minecraft.sounds.SoundEvent.createVariableRangeEvent(net.minecraft.resources.Identifier.fromNamespaceAndPath(dev.marceline.Marceline.MOD_ID, "ui.click"))
    }

    fun playClickSound(pitch: Float) =
        if (ClickGui.riftClickSound.value) SoundUtils.playSound(riftClick, 0.55f, pitch)
        else SoundUtils.playSound(SoundEvents.UI_BUTTON_CLICK, 0.25f, pitch)
}