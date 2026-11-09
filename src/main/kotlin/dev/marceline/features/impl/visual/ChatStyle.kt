package dev.marceline.features.impl.visual

import dev.marceline.features.Feature

/**
 * Chat in the Marceline look: the black line backgrounds become a dark wine glass (same see-through amount as
 * the game's Text Background Opacity), the scroll bar turns gold and the tag bar at the left of system lines wine. Hooked into the chat's own fills (MixinChatStyle).
 */
object ChatStyle: Feature("Draws the chat background in the Marceline dark wine glass with a gold scroll bar.", toggled = true) {
    private const val WINE_GLASS = 0x1E0B10
    private const val GOLD = 0xC9A46A
    private const val VANILLA_SCROLL = 0xCCCCCC

    private const val VANILLA_SYSTEM_TAG = 0xD0D0D0
    private const val VANILLA_MODIFIED_TAG = 0x606060

    /** Colour of the bar at the left of tagged lines: wine for system messages, a muted rose for modified ones. */
    @JvmStatic
    fun tagColor(rgb: Int): Int {
        if (! enabled) return rgb
        return when (rgb and 0xFFFFFF) {
            VANILLA_SYSTEM_TAG -> 0x8E2236
            VANILLA_MODIFIED_TAG -> 0x6A3A44
            else -> rgb
        }
    }

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
