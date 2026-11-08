package dev.riftwalk.ui.hud

import dev.riftwalk.ui.clickgui.components.settings.Style
import dev.riftwalk.utils.ColorUtils.withAlpha
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Color
import kotlin.math.roundToInt
import kotlin.reflect.jvm.jvmName

abstract class HudElement {
    open val name = this::class.simpleName ?: this::class.jvmName

    abstract val toggle: Boolean
    open val shouldDraw = true
    open val centered: () -> Boolean = { false }

    var x = defaultX
    var y = defaultY
    var scale = defaultScale

    var width = 0f
    var height = 0f

    var defaults: HudElement.() -> Unit = {
        x = defaultX
        y = defaultY
        scale = defaultScale
    }

    /** False for elements that draw their own background (scoreboard, titles, route status...). */
    var themedPanel = true

    var isDragging = false
    private var dragX = 0f
    private var dragY = 0f

    abstract fun draw(ctx: GuiGraphicsExtractor, example: Boolean): Pair<Number, Number>

    private val editorHover = dev.riftwalk.ui.utils.Animation(140, 0f)

    open fun drawBackground(ctx: GuiGraphicsExtractor, mx: Int, my: Int) {
        val scaledW = width * scale
        val scaledH = height * scale
        val centeredOffset = if (centered()) scaledW / 2f else 0f
        val hovered = mx >= x - centeredOffset && mx <= x - centeredOffset + scaledW && my >= y && my <= y + scaledH
        editorHover.update(if (isDragging || hovered) 1f else 0f)
        val hv = editorHover.value
        val left = x - centeredOffset - 2f
        val top = y - 2f
        val w = scaledW + 4f
        val h = scaledH + 4f

        // accent outline that fades in, then the dark rounded panel on top of it
        if (hv > 0.01f) {
            Style.roundRect(ctx, left - 2f, top - 2f, w + 4f, h + 4f, 5f, Style.accentColor.withAlpha((45 * hv).toInt()))
            Style.roundRect(ctx, left - 1f, top - 1f, w + 2f, h + 2f, 4f, Style.mix(Style.accentColor, Style.accent2, 0.5f).withAlpha((200 * hv).toInt()))
        }
        else Style.roundRect(ctx, left - 0.5f, top - 0.5f, w + 1f, h + 1f, 3.5f, Color(255, 255, 255, 22))
        Style.roundRect(ctx, left, top, w, h, 3f, Color(15, 11, 23, (150 + 40 * hv).toInt()))
    }

    fun renderElement(ctx: GuiGraphicsExtractor, example: Boolean) {
        if (! toggle) return

        ctx.pose().pushMatrix()
        ctx.pose().translate(x, y)
        ctx.pose().scale(scale, scale)

        val gui = dev.riftwalk.features.impl.dev.ClickGui
        if (! example && themedPanel && gui.hudPanels.value && width > 0f && height > 0f) {
            val left = if (centered()) - width / 2f else 0f
            val px = left - 5f
            val py = - 3.5f
            val pw = width + 10f
            val ph = height + 7f
            Style.drawShadow(ctx, px, py, pw, ph, 4.5f, 0.55f)
            Style.roundRect(ctx, px - 0.5f, py - 0.5f, pw + 1f, ph + 1f, 5f, Color(255, 255, 255, 20)) // hairline edge
            Style.roundRect(ctx, px, py, pw, ph, 4.5f, Color(16, 11, 26, 172))
            // light on glass: a faint sheen across the top
            with(dev.riftwalk.utils.render.Render2D) {
                ctx.drawVerticalGradient(px + 3f, py + 0.5f, pw - 6f, minOf(9f, ph / 2f), Color(255, 255, 255, 13), Color(255, 255, 255, 0))
                // accent strip, purple into pink
                ctx.drawVerticalGradient(px + 1.5f, 0f, 1.5f, height, Style.accentColor.withAlpha(230), Style.accent2.withAlpha(230))
            }
        }

        val prevFont = dev.riftwalk.utils.render.UiFont.active
        val prevThemed = dev.riftwalk.utils.render.UiFont.themed
        dev.riftwalk.utils.render.UiFont.active = gui.hudFont.value
        dev.riftwalk.utils.render.UiFont.themed = gui.hudColors.value
        try {
            draw(ctx, example).run {
                width = first.toFloat()
                height = second.toFloat()
            }
        } finally {
            dev.riftwalk.utils.render.UiFont.active = prevFont
            dev.riftwalk.utils.render.UiFont.themed = prevThemed
        }

        ctx.pose().popMatrix()
    }

    fun drawEditor(ctx: GuiGraphicsExtractor, mx: Int, my: Int, gridSize: Int = 0) {
        if (! toggle) return

        if (isDragging) {
            x = mx - dragX
            y = my - dragY
            if (gridSize > 0) {
                x = (x / gridSize).roundToInt() * gridSize.toFloat()
                y = (y / gridSize).roundToInt() * gridSize.toFloat()
            }
        }

        drawBackground(ctx, mx, my)
        renderElement(ctx, true)
    }

    open fun isHovered(mx: Int, my: Int): Boolean {
        val scaledW = width * scale
        val scaledH = height * scale
        val centeredOffset = (if (centered()) scaledW / 2 else 0).toInt()
        return mx >= x - centeredOffset && mx <= x - centeredOffset + scaledW && my >= y && my <= y + scaledH
    }

    fun startDragging(mx: Int, my: Int) {
        if (isHovered(mx, my)) {
            isDragging = true
            dragX = mx - x
            dragY = my - y
        }
    }

    companion object {
        const val defaultX = 20f
        const val defaultY = 20f
        const val defaultScale = 1f
    }
}