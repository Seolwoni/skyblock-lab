package dev.marceline.features.impl.visual

import dev.marceline.config.types.ToggleSetting
import dev.marceline.features.Feature
import dev.marceline.ui.menu.MarcelinePauseScreen
import dev.marceline.ui.menu.MarcelineTitleScreen
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen

/**
 * The Marceline look for the main menu and the ESC menu: blurred background, the Marceline mark, flat buttons.
 * Swapped in where the game opens its own screen (see MixinGuiScreen.onSetScreen).
 */
object MenuScreens: Feature("Marceline title screen and ESC menu: blurred background, the Marceline mark and flat buttons.", toggled = true) {
    /** Only looks: "All Off" (MasterSwitch) leaves it on, so the ESC menu keeps the Marceline style. */
    override val keepOnMasterOff get() = true

    private val title by ToggleSetting("Title Screen", true).withDescription("The main menu you see after starting the game")
    private val pause by ToggleSetting("ESC Menu", true).withDescription("The menu that opens with ESC in a world or on a server")

    /** The selected row's outline in the game's lists: gold when focused, a dim gold otherwise (see MixinListSelection). */
    @JvmStatic
    fun selectionOutline(argb: Int): Int = when {
        ! enabled -> argb
        argb == -1 -> 0xFFC9A46A.toInt()
        argb == -8355712 -> 0xFF6E5A48.toInt()
        else -> argb
    }

    /** The selected row's inside: dark wine instead of black. */
    @JvmStatic
    fun selectionInside(argb: Int): Int = if (enabled && argb == -16777216) 0xFF180A0E.toInt() else argb

    /** Our screen in place of [screen], or null to keep the game's own. */
    @JvmStatic
    fun replace(screen: Screen?): Screen? {
        if (! enabled || screen == null) return null
        return when {
            screen is TitleScreen && screen !is MarcelineTitleScreen && title.value -> MarcelineTitleScreen()
            screen is PauseScreen && screen !is MarcelinePauseScreen && screen.showsPauseMenu() && pause.value -> MarcelinePauseScreen()
            else -> null
        }
    }
}
