package dev.marceline.ui.menu

import dev.marceline.Marceline.mc
import dev.marceline.features.impl.dev.ClickGui
import dev.marceline.utils.render.UiFont
import net.minecraft.network.chat.Component
import net.minecraft.client.gui.screens.options.OptionsScreen
import net.minecraft.client.gui.screens.options.OptionsSubScreen

/**
 * The game's Options screens with their button and slider labels in the menu font (Pretendard), so they match
 * the rest of Marceline. Only the font changes: words, sizes and layout stay the game's. Parts of a label that
 * ask for a font of their own keep it.
 */
object OptionsFont {
    private fun here(): Boolean {
        if (! ClickGui.optionsFont.value) return false
        val s = mc.gui.screen() ?: return false
        return s is OptionsScreen || s is OptionsSubScreen
    }

    @JvmStatic
    fun label(c: Component): Component =
        if (! here()) c else Component.empty().withStyle { it.withFont(UiFont.regular) }.append(c)
}
