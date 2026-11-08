package dev.riftwalk.features.impl.visual

import dev.riftwalk.config.types.ToggleSetting
import dev.riftwalk.features.Feature
import dev.riftwalk.ui.menu.RiftPauseScreen
import dev.riftwalk.ui.menu.RiftTitleScreen
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen

/**
 * The Riftwalk look for the main menu and the ESC menu: blurred background, the Riftwalk mark, flat buttons.
 * Swapped in where the game opens its own screen (see MixinGuiScreen.onSetScreen).
 */
object MenuScreens: Feature("Riftwalk title screen and ESC menu: blurred background, the Riftwalk mark and flat buttons.", toggled = true) {
    private val title by ToggleSetting("Title Screen", true).withDescription("The main menu you see after starting the game")
    private val pause by ToggleSetting("ESC Menu", true).withDescription("The menu that opens with ESC in a world or on a server")

    /** Our screen in place of [screen], or null to keep the game's own. */
    @JvmStatic
    fun replace(screen: Screen?): Screen? {
        if (! enabled || screen == null) return null
        return when {
            screen is TitleScreen && screen !is RiftTitleScreen && title.value -> RiftTitleScreen()
            screen is PauseScreen && screen !is RiftPauseScreen && screen.showsPauseMenu() && pause.value -> RiftPauseScreen()
            else -> null
        }
    }
}
