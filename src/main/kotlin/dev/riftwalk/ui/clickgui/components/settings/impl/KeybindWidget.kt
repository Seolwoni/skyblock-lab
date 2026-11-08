package dev.riftwalk.ui.clickgui.components.settings.impl

import com.mojang.blaze3d.platform.InputConstants
import dev.riftwalk.config.types.KeybindSetting
import dev.riftwalk.ui.clickgui.components.settings.Style
import dev.riftwalk.ui.clickgui.components.settings.Widget
import dev.riftwalk.ui.utils.Animation
import dev.riftwalk.utils.render.Render2D.drawString
import dev.riftwalk.utils.render.RenderHelper.width
import net.minecraft.client.gui.GuiGraphicsExtractor

class KeybindWidget(config: KeybindSetting): Widget<Int>(config) {
    private inline val cfg get() = config as KeybindSetting

    private val hoverAnim = Animation(200)
    private var listening = false

    override fun draw(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val isHovered = mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height
        hoverAnim.update(if (isHovered) 1f else 0f)

        Style.drawBackground(ctx, x, y, width, height)
        Style.drawHoverBar(ctx, x, y, height, hoverAnim.value)
        Style.drawNudgedText(ctx, name, x + 8f, y + 6f, hoverAnim.value)

        val raw = cfg.displayName()
        val unset = ! listening && (raw.isBlank() || raw.equals("NONE", true) || raw.equals("UNKNOWN", true))
        val bindText = when {
            listening -> "Press a key…"
            unset -> "Not set"
            else -> raw
        }
        val bw = bindText.width() + 10f
        val bx = x + width - bw - 6f
        if (unset) Style.roundRect(ctx, bx, y + 3f, bw, 13f, 6.5f, java.awt.Color(255, 255, 255, 10 + (12 * hoverAnim.value).toInt()))
        else Style.valuePill(ctx, bx, y + 3f, bw, 13f, listening, hoverAnim.value)
        ctx.drawString(bindText, bx + 5f, y + 6f, when {
            listening -> Style.accentColor
            unset -> java.awt.Color(138, 127, 163)
            else -> java.awt.Color.WHITE
        }, shadow = false)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val isInside = mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height

        if (listening) {
            this.value = button
            cfg.isMouse = true
            cfg.scanCode = 0
            this.listening = false
            Style.playClickSound(1f)
            return true
        }

        if (isInside) {
            listening = true
            Style.playClickSound(1f)
            return true
        }
        return false
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (listening) {
            if (keyCode == InputConstants.KEY_ESCAPE) {
                listening = false
                return true
            }

            if (keyCode == InputConstants.KEY_BACKSPACE) {
                value = InputConstants.UNKNOWN.value
                cfg.isMouse = false
            }
            else {
                this.value = keyCode
                cfg.scanCode = scanCode
                cfg.isMouse = false
            }
            listening = false
            return true
        }
        return false
    }
}
