package dev.marceline.ui.clickgui.components.settings.impl

import com.mojang.blaze3d.platform.InputConstants

import dev.marceline.config.types.DropdownSetting
import dev.marceline.ui.clickgui.components.settings.Style
import dev.marceline.ui.clickgui.components.settings.Widget
import dev.marceline.ui.utils.Animation
import dev.marceline.utils.render.Render2D.drawRect
import dev.marceline.utils.render.Render2D.drawHorizontalGradient
import dev.marceline.utils.ColorUtils.withAlpha
import dev.marceline.utils.render.Render2D.drawString
import dev.marceline.utils.render.Render2D.scissor
import dev.marceline.utils.render.RenderHelper.width
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Color

class DropdownWidget(config: DropdownSetting): Widget<Int>(config) {
    private inline val cfg get() = config as DropdownSetting

    private var expanded = false
    private val openAnim = Animation(250)
    private val hoverAnim = Animation(200)

    override val height get() = 20 + (openAnim.value * (cfg.options.size * 16)).toInt()

    private val optionHover = HashMap<Int, Animation>()

    override fun draw(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val isHovered = mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + 20
        openAnim.update(if (expanded) 1f else 0f)
        hoverAnim.update(if (isHovered || expanded) 1f else 0f)
        val open = openAnim.value

        ctx.drawRect(x, y, width, 20f, Style.settingBackgroundColor)
        Style.drawHoverBar(ctx, x, y, 20f, hoverAnim.value)
        Style.drawNudgedText(ctx, name, x + 8f, y + 6f, hoverAnim.value)

        // current value in a soft pill with a chevron that flips when open
        val valStr = cfg.options[value]
        val pw = valStr.width() + 20f
        val px = x + width - pw - 6f
        Style.roundRect(ctx, px, y + 3f, pw, 14f, 7f, Style.mix(Color(255, 255, 255, 12), Style.accentColor.withAlpha(70), maxOf(open, hoverAnim.value * 0.5f)))
        ctx.drawString(valStr, px + 6f, y + 6f, Style.mix(Color(196, 184, 224), Color.WHITE, hoverAnim.value), shadow = false)
        ctx.drawString(if (open > 0.5f) "▴" else "▾", px + pw - 11f, y + 5.5f, Style.mix(Color(138, 127, 163), Style.accent2, open), shadow = false)

        if (open > 0.01f) {
            ctx.scissor(x, y, width, height)
            val listH = cfg.options.size * 16f
            Style.roundRect(ctx, x + 4f, y + 21f, width - 8f, listH * open - 1f, 4f, Color(11, 8, 18, (220 * open).toInt()))
            var oy = y + 21f
            cfg.options.forEachIndexed { index, opt ->
                val hov = expanded && mouseX >= x + 4 && mouseX <= x + width - 4 && mouseY >= oy && mouseY <= oy + 16
                val hv = optionHover.getOrPut(index) { Animation(120, 0f) }.also { it.update(if (hov) 1f else 0f) }.value
                // options slide in one after another
                val stagger = ((open * (cfg.options.size + 2) - index) / 3f).coerceIn(0f, 1f)
                val slide = (1f - stagger) * 6f
                if (index == value) {
                    ctx.drawHorizontalGradient(x + 4f, oy, width - 8f, 16f, Style.accentColor.withAlpha((90 * stagger).toInt()), Style.accent2.withAlpha((20 * stagger).toInt()))
                    Style.roundRect(ctx, x + 6f, oy + 4f, 2f, 8f, 1f, Style.accent2.withAlpha((255 * stagger).toInt()))
                }
                else if (hv > 0.01f) ctx.drawRect(x + 4f, oy, width - 8f, 16f, Color(255, 255, 255, (16 * hv).toInt()))

                val base = if (index == value) Color.WHITE else Style.mix(Color(138, 127, 163), Color(237, 231, 255), hv)
                ctx.drawString(opt, x + 13f + slide + 2f * hv, oy + 4f, base.withAlpha((255 * stagger).toInt().coerceAtLeast(8)), shadow = false)
                oy += 16
            }
            ctx.disableScissor()
        }
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + 20) {
            if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                expanded = ! expanded
                Style.playClickSound(1f)
                return true
            }
        }

        if (expanded && mouseX >= x && mouseX <= x + width && mouseY >= y + 20 && mouseY <= y + height) {
            var optionY = y + 21
            cfg.options.forEachIndexed { index, _ ->
                if (mouseX >= x && mouseX <= x + width && mouseY >= optionY && mouseY <= optionY + 16) {
                    value = index
                    Style.playClickSound(1f)
                    expanded = false
                    return true
                }
                optionY += 16
            }
        }

        if (expanded) expanded = false
        return false
    }
}