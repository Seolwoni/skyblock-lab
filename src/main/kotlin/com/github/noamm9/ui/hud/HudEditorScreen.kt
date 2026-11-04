package com.github.noamm9.ui.hud

import com.github.noamm9.config.ConfigManager
import com.github.noamm9.features.FeatureManager
import com.github.noamm9.features.impl.dev.ClickGui
import com.github.noamm9.ui.clickgui.components.settings.Style
import com.github.noamm9.ui.utils.Animation
import com.github.noamm9.ui.utils.Resolution
import com.github.noamm9.utils.ColorUtils.withAlpha
import com.github.noamm9.utils.render.Render2D.drawCenteredString
import com.github.noamm9.utils.render.Render2D.drawHorizontalGradient
import com.github.noamm9.utils.render.Render2D.drawRect
import com.github.noamm9.utils.render.Render2D.drawString
import com.github.noamm9.utils.render.Render2D.drawVerticalGradient
import com.github.noamm9.utils.render.RenderHelper.width
import com.github.noamm9.utils.render.UiFont
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import java.awt.Color

/**
 * Void Rift HUD editor: dimmed world, faint purple grid, a floating toolbar at the bottom
 * and a name tag on the element under the mouse. Drag to move, scroll to resize.
 */
class HudEditorScreen: Screen(Component.literal("HudEditor")) {
    private val huds = FeatureManager.hudElements.filter(HudElement::toggle)
    private var resetConfirmed = false
    private var gridEnabled = false
    private var gridSize = 10

    private val openAnim = Animation(180, 0f)
    private val gridAnim = Animation(180, 0f)
    private val hoverAnims = HashMap<String, Animation>()

    private val textMain = Color(237, 231, 255)
    private val textSoft = Color(196, 184, 224)
    private val textFaint = Color(138, 127, 163)

    private data class Btn(val id: String, val label: String, val x: Float, val y: Float, val w: Float, val h: Float)

    private fun buttons(): List<Btn> {
        val h = 18f
        val y = Resolution.height - 34f
        val labels = listOf(
            "grid" to if (gridEnabled) "Grid  ${gridSize}px" else "Grid  Off",
            "minus" to "−",
            "plus" to "+",
            "arrange" to "Arrange",
            "reset" to if (resetConfirmed) "Confirm reset?" else "Reset",
            "done" to "Done",
        )
        val widths = labels.map { (id, l) -> if (id == "minus" || id == "plus") 18f else maxOf(44f, textW(l) + 16f) }
        val gap = 4f
        val total = widths.sum() + gap * (widths.size - 1)
        var x = Resolution.width / 2f - total / 2f
        return labels.mapIndexed { i, (id, l) -> Btn(id, l, x, y, widths[i], h).also { x += widths[i] + gap } }
    }

    private fun textW(s: String) = if (UiFont.active) UiFont.width(s) else s.width()

    private fun drawGrid(ctx: GuiGraphicsExtractor, strength: Float) {
        if (strength <= 0.01f) return
        val w = Resolution.width.toInt()
        val h = Resolution.height.toInt()
        val minor = Style.accentColor.withAlpha((22 * strength).toInt())
        val major = Style.accentColor.withAlpha((45 * strength).toInt())

        var i = 0
        var x = 0
        while (x <= w) {
            ctx.drawRect(x, 0, 1, h, if (i % 5 == 0) major else minor)
            x += gridSize; i ++
        }
        i = 0
        var y = 0
        while (y <= h) {
            ctx.drawRect(0, y, w, 1, if (i % 5 == 0) major else minor)
            y += gridSize; i ++
        }
    }

    /** Centre guides: always faintly there, brighter while dragging. */
    private fun drawGuides(ctx: GuiGraphicsExtractor, dragging: Boolean) {
        val w = Resolution.width
        val h = Resolution.height
        val a = if (dragging) 120 else 40
        ctx.drawHorizontalGradient(0, h / 2, w / 2, 1, Style.accentColor.withAlpha(0), Style.accentColor.withAlpha(a))
        ctx.drawHorizontalGradient(w / 2, h / 2, w / 2, 1, Style.accent2.withAlpha(a), Style.accent2.withAlpha(0))
        ctx.drawVerticalGradient(w / 2, 0, 1, h / 2, Style.accentColor.withAlpha(0), Style.accentColor.withAlpha(a))
        ctx.drawVerticalGradient(w / 2, h / 2, 1, h / 2, Style.accent2.withAlpha(a), Style.accent2.withAlpha(0))
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        Resolution.push(graphics)

        val mX = Resolution.getMouseX(mouseX)
        val mY = Resolution.getMouseY(mouseY)
        openAnim.update(1f)
        gridAnim.update(if (gridEnabled) 1f else 0f)
        val o = openAnim.value

        // dim the world, a little darker at the edges
        graphics.drawRect(0, 0, Resolution.width, Resolution.height, Color(6, 2, 14, (110 * o).toInt()))
        graphics.drawVerticalGradient(0, 0, Resolution.width, 40, Color(6, 2, 14, (90 * o).toInt()), Color(6, 2, 14, 0))
        graphics.drawVerticalGradient(0, Resolution.height - 60, Resolution.width, 60, Color(6, 2, 14, 0), Color(6, 2, 14, (120 * o).toInt()))

        drawGrid(graphics, gridAnim.value * o)

        val draggedElement = huds.find { it.isDragging }
        drawGuides(graphics, draggedElement != null)

        val effectiveGridSize = if (gridEnabled) gridSize else 0
        for (hud in huds) hud.drawEditor(graphics, mX, mY, effectiveGridSize)

        val hoveredElement = if (draggedElement == null) huds.find { it.isHovered(mX, mY) } else null
        val activeElement = draggedElement ?: hoveredElement

        UiFont.active = ClickGui.customFont.value
        try {
            if (activeElement != null) drawNameTag(graphics, activeElement)
            else if (huds.isEmpty()) graphics.drawCenteredString("No HUD elements are enabled", Resolution.width / 2, Resolution.height / 2 - 4, textFaint, shadow = false)
            drawHint(graphics)
            drawToolbar(graphics, mX.toFloat(), mY.toFloat(), o)
        } finally {
            UiFont.active = false
        }

        Resolution.pop(graphics)
    }

    /** Pill above the element: name, position and scale. */
    private fun drawNameTag(ctx: GuiGraphicsExtractor, el: HudElement) {
        val w = el.width * el.scale
        val left = el.x - if (el.centered()) w / 2f else 0f
        val text = "${el.name}"
        val info = "${el.x.toInt()}, ${el.y.toInt()}  ·  ${"%.1f".format(el.scale)}×"
        val tw = textW(text) + 8f + textW(info) + 14f
        var ty = el.y - 16f
        if (ty < 4f) ty = el.y + el.height * el.scale + 4f
        val tx = (left + w / 2f - tw / 2f).coerceIn(4f, Resolution.width - tw - 4f)
        Style.roundRect(ctx, tx, ty, tw, 13f, 6.5f, Color(15, 11, 23, 235))
        Style.gradientPill(ctx, tx, ty + 12f, tw, 1f, Style.accentColor, Style.accent2)
        ctx.drawString(text, tx + 7f, ty + 3f, textMain, shadow = false)
        ctx.drawString(info, tx + 7f + textW(text) + 8f, ty + 3f, textFaint, shadow = false)
    }

    private fun drawHint(ctx: GuiGraphicsExtractor) {
        val hint = "Drag to move  ·  Scroll to resize  ·  Esc to close"
        ctx.drawCenteredString(hint, Resolution.width / 2, Resolution.height - 50, textFaint, shadow = false)
    }

    private fun drawToolbar(ctx: GuiGraphicsExtractor, mx: Float, my: Float, o: Float) {
        val bs = buttons()
        if (bs.isEmpty()) return
        val px = bs.first().x - 5f
        val pw = bs.last().x + bs.last().w + 5f - px
        val py = bs.first().y - 5f
        val ph = bs.first().h + 10f
        val lift = (1f - o) * 10f
        ctx.pose().pushMatrix()
        ctx.pose().translate(0f, lift)
        Style.drawShadow(ctx, px, py, pw, ph, 6f, o)
        Style.roundRect(ctx, px, py, pw, ph, 6f, Color(21, 15, 32, 245))
        ctx.drawHorizontalGradient(px + 6f, py, pw / 2f - 6f, 1f, Style.accentColor.withAlpha(0), Style.accentColor.withAlpha(180))
        ctx.drawHorizontalGradient(px + pw / 2f, py, pw / 2f - 6f, 1f, Style.accent2.withAlpha(180), Style.accent2.withAlpha(0))

        for (b in bs) {
            val hovered = mx in b.x .. b.x + b.w && my in b.y .. b.y + b.h
            val hv = hoverAnims.getOrPut(b.id) { Animation(140, 0f) }.also { it.update(if (hovered) 1f else 0f) }.value
            val active = (b.id == "grid" && gridEnabled) || (b.id == "reset" && resetConfirmed)
            when {
                b.id == "reset" && resetConfirmed -> Style.roundRect(ctx, b.x, b.y, b.w, b.h, 4f, Color(150, 40, 70))
                b.id == "done" -> Style.gradientPill(ctx, b.x, b.y, b.w, b.h, Style.accentColor.withAlpha(200 + (55 * hv).toInt()), Style.accent2.withAlpha(200 + (55 * hv).toInt()))
                active -> Style.roundRect(ctx, b.x, b.y, b.w, b.h, 4f, Style.mix(Color(52, 36, 84), Color(70, 48, 110), hv))
                else -> Style.roundRect(ctx, b.x, b.y, b.w, b.h, 4f, Style.mix(Color(32, 24, 46), Color(45, 33, 66), hv))
            }
            val color = when {
                b.id == "done" || (b.id == "reset" && resetConfirmed) -> Color.WHITE
                b.id == "reset" -> Style.mix(Color(240, 120, 150), Color(255, 170, 190), hv)
                active -> textMain
                else -> Style.mix(textSoft, textMain, hv)
            }
            ctx.drawCenteredString(b.label, b.x + b.w / 2f, b.y + 5f, color, shadow = false)
        }
        ctx.pose().popMatrix()
    }

    private fun clickButton(id: String) {
        Style.playClickSound(1f)
        if (id != "reset") resetConfirmed = false
        when (id) {
            "grid" -> gridEnabled = ! gridEnabled
            "minus" -> { gridSize = (gridSize - 5).coerceAtLeast(5); gridEnabled = true }
            "plus" -> { gridSize = (gridSize + 5).coerceAtMost(50); gridEnabled = true }
            "done" -> onClose()
            "arrange" -> arrange()
            "reset" -> {
                if (! resetConfirmed) { resetConfirmed = true; return }
                resetConfirmed = false
                FeatureManager.hudElements.forEach {
                    it.x = HudElement.defaultX
                    it.y = HudElement.defaultY
                    it.scale = HudElement.defaultScale
                    it.defaults.invoke(it)
                }
            }
        }
    }

    /** Lays every enabled element out so none overlap: columns from the top-left, keeping their current order. */
    private fun arrange() {
        val gap = 8f
        val maxY = Resolution.height - 50f
        var x = 8f
        var y = 8f
        var colW = 0f
        for (el in huds.sortedWith(compareBy({ it.x }, { it.y }))) {
            val w = el.width * el.scale + 10f
            val h = el.height * el.scale + 7f
            if (w <= 10f || h <= 7f) continue
            if (y + h > maxY && y > 8f) { x += colW + gap; y = 8f; colW = 0f }
            el.x = x + (if (el.centered()) w / 2f - 5f else 5f)
            el.y = y + 3.5f
            y += h + gap
            colW = maxOf(colW, w)
        }
        ConfigManager.save()
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontal: Double, vertical: Double): Boolean {
        val mX = Resolution.getMouseX(mouseX)
        val mY = Resolution.getMouseY(mouseY)
        val hud = huds.find { it.isDragging } ?: huds.find { it.isHovered(mX, mY) }

        if (hud != null) {
            val increment = (vertical * 0.1).toFloat()
            hud.scale = (hud.scale + increment).coerceIn(0.5f, 5.0f)
            return true
        }

        if (gridEnabled) {
            val step = (vertical * 5).toInt()
            if (step != 0) {
                gridSize = (gridSize + step).coerceIn(5, 50)
                return true
            }
        }

        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical)
    }

    override fun mouseClicked(mouseButtonEvent: MouseButtonEvent, bl: Boolean): Boolean {
        val mX = Resolution.getMouseX(mouseButtonEvent.x)
        val mY = Resolution.getMouseY(mouseButtonEvent.y)

        if (mouseButtonEvent.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            UiFont.active = ClickGui.customFont.value
            val hit = try { buttons().firstOrNull { mX >= it.x && mX <= it.x + it.w && mY >= it.y && mY <= it.y + it.h } } finally { UiFont.active = false }
            if (hit != null) {
                clickButton(hit.id)
                return true
            }
            huds.forEach {
                it.startDragging(mX, mY)
                if (it.isDragging) return true
            }
        }

        return super.mouseClicked(mouseButtonEvent, bl)
    }

    override fun keyPressed(keyEvent: KeyEvent): Boolean {
        if (keyEvent.key == InputConstants.KEY_G) {
            gridEnabled = ! gridEnabled
            return true
        }
        return super.keyPressed(keyEvent)
    }

    override fun mouseReleased(mouseButtonEvent: MouseButtonEvent): Boolean {
        for (hud in huds) hud.isDragging = false
        return super.mouseReleased(mouseButtonEvent)
    }

    override fun onClose() {
        ConfigManager.save()
        super.onClose()
    }
}
