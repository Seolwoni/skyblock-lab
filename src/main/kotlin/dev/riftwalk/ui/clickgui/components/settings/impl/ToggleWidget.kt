package dev.riftwalk.ui.clickgui.components.settings.impl

import com.mojang.blaze3d.platform.InputConstants

import dev.riftwalk.config.types.ToggleSetting
import dev.riftwalk.ui.clickgui.components.settings.Style
import dev.riftwalk.ui.clickgui.components.settings.Widget
import dev.riftwalk.ui.utils.Animation
import dev.riftwalk.utils.ColorUtils.lerp
import dev.riftwalk.utils.render.Render2D.drawRect
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Color

class ToggleWidget(config: ToggleSetting): Widget<Boolean>(config) {
    private val toggleAnim = Animation(200, if (config.value) 1f else 0f)
    private val hoverAnim = Animation(200, 0f)

    override fun draw(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val isHovered = mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height
        toggleAnim.update(if (value) 1f else 0f)
        hoverAnim.update(if (isHovered) 1f else 0f)

        Style.drawBackground(ctx, x, y, width, height)
        Style.drawHoverBar(ctx, x, y, height, hoverAnim.value)
        Style.drawNudgedText(ctx, name, x + 8f, y + 6f, hoverAnim.value)

        val sw = 22f
        val sh = 11f
        Style.drawSwitch(ctx, x + width - sw - 8f, y + (height / 2f) - (sh / 2f), sw, sh, toggleAnim.value)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == InputConstants.MOUSE_BUTTON_LEFT && mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height) {
            value = ! value
            Style.playClickSound(1f)
            return true
        }
        return false
    }
}