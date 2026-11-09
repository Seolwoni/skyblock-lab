package dev.marceline.features.impl.visual

import dev.marceline.features.Feature

/**
 * Chat in the Marceline look: the black line backgrounds become a dark wine glass (same see-through amount as
 * the game's Text Background Opacity), the scroll bar turns gold. Hooked into the chat's own fills (MixinChatStyle).
 */
object ChatStyle: Feature("Draws the chat background in the Marceline dark wine glass with a gold scroll bar.", toggled = true) {
    private const val WINE_GLASS = 0x1E0B10
    private const val GOLD = 0xC9A46A
    private const val VANILLA_SCROLL = 0xCCCCCC

    /** The colour a chat fill should use instead of [argb]. */
    @JvmStatic
    fun recolor(argb: Int): Int {
        if (! enabled) return argb
        val alpha = argb and 0xFF000000.toInt()
        return when (argb and 0xFFFFFF) {
            0 -> alpha or WINE_GLASS
            VANILLA_SCROLL -> alpha or GOLD
            else -> argb
        }
    }
}
