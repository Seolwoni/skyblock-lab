package dev.riftwalk.ui.clickgui.components.settings.impl

import dev.riftwalk.config.types.UnitSetting
import dev.riftwalk.ui.clickgui.components.settings.Widget
import dev.riftwalk.utils.render.Render2D.drawRect
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Color

class SeparatorWidget(name: String = ""): Widget<Unit>(UnitSetting(name)) {
    override val height = 10
    override fun draw(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        ctx.drawRect(x + 10f, y + 5f, width - 20f, 0.5f, Color(255, 255, 255, 30))
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int) = false
}