package dev.marceline.ui.hud

import dev.marceline.ui.clickgui.components.settings.Style
import dev.marceline.utils.ColorUtils.withAlpha
import net.minecraft.client.gui.GuiGraphicsExtractor
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

    private val editorHover = dev.marceline.ui.utils.Animation(140, 0f)

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

        // a gold outline that fades in on hover / drag, then the card itself
        if (hv > 0.01f) {
            Style.roundRect(ctx, left - 2.5f, top - 2.5f, w + 5f, h + 5f, 6f, Style.gold.withAlpha((34 * hv).toInt()))
            Style.roundRect(ctx, left - 1f, top - 1f, w + 2f, h + 2f, 4.5f, Style.gold.withAlpha((190 * hv).toInt()))
        }
        HudCard.draw(ctx, left, top, w, h, 3.5f, glassAlpha = (150 + 40 * hv).toInt(), rule = hv < 0.5f)
    }

    fun renderElement(ctx: GuiGraphicsExtractor, example: Boolean) {
        if (! toggle) return

        ctx.pose().pushMatrix()
        ctx.pose().translate(x, y)
        ctx.pose().scale(scale, scale)

        val gui = dev.marceline.features.impl.dev.ClickGui
        if (! example && themedPanel && gui.hudPanels.value && width > 0f && height > 0f) {
            val left = if (centered()) - width / 2f else 0f
            val px = left - 5f
            val py = - 3.5f
            val pw = width + 10f
            val ph = height + 7f
            HudCard.draw(ctx, px, py, pw, ph)
        }

        val prevFont = dev.marceline.utils.render.UiFont.active
        val prevThemed = dev.marceline.utils.render.UiFont.themed
        dev.marceline.utils.render.UiFont.active = gui.hudFont.value
        dev.marceline.utils.render.UiFont.themed = gui.hudColors.value
        try {
            draw(ctx, example).run {
                width = first.toFloat()
                height = second.toFloat()
            }
        } finally {
            dev.marceline.utils.render.UiFont.active = prevFont
            dev.marceline.utils.render.UiFont.themed = prevThemed
        }

        ctx.pose().popMatrix()
    }

    /** [snap] may nudge the element while it is dragged without a grid (alignment with other elements). */
    fun drawEditor(ctx: GuiGraphicsExtractor, mx: Int, my: Int, gridSize: Int = 0, snap: ((HudElement) -> Unit)? = null) {
        if (! toggle) return

        if (isDragging) {
            x = mx - dragX
            y = my - dragY
            if (gridSize > 0) {
                x = (x / gridSize).roundToInt() * gridSize.toFloat()
                y = (y / gridSize).roundToInt() * gridSize.toFloat()
            }
            else snap?.invoke(this)
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