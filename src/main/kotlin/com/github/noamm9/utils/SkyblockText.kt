package com.github.noamm9.utils

/**
 * Every server text and item ID the automation reads, in one place. A private server that words something
 * differently (or uses other item IDs) only needs this file changed. Matching is case-insensitive where it says so.
 */
object SkyblockText {
    // ---- fishing: what floats over the water
    /** Over the bobber on a bite (armor stand name or text display). */
    const val BITE = "!!!"
    /** The floating label of a fishing hotspot (case-insensitive). */
    const val HOTSPOT = "HOTSPOT"

    // ---- fishing: chat / lore
    const val DOUBLE_HOOK = "Double Hook"
    val TROPHY_CATCH = Regex("TROPHY FISH! You caught an? (.+?) (BRONZE|SILVER|GOLD|DIAMOND)\\.?$")
    val BAIT_REMAINING = Regex("Bait Remaining: ([\\d,]+)")
    /** "[Lv45] Sea Walker 1,200/1,200❤" -> group 1 = "Sea Walker". */
    val MOB_TAG = Regex("^(?:\\[Lv\\d+] )?(.+?) [\\d,.]+[kKmMbB]?(?:/[\\d,.]+[kKmMbB]?)?❤")
    /** "1,200/1,200❤" -> current, max. */
    val MOB_HEALTH = Regex("([\\d,.]+[kKmMbB]?)/([\\d,.]+[kKmMbB]?)❤")
    /** A creature already at 0 health (it lingers a moment). */
    val MOB_DEAD = Regex("(?:^|\\s)0(?:/[\\d,.]+[kKmMbB]?)?❤")

    // ---- mana
    const val NOT_ENOUGH_MANA_BAR = "NOT ENOUGH MANA"
    const val NOT_ENOUGH_MANA_CHAT = "do not have enough mana"

    // ---- item IDs
    val TELEPORT_SWORDS = setOf("ASPECT_OF_THE_VOID", "ASPECT_OF_THE_END")
    val WITHER_BLADES = setOf("HYPERION", "ASTRAEA", "SCYLLA", "VALKYRIE")
    const val SOUL_WHIP = "SOUL_WHIP"
    const val FLAMING_FLAY = "FLAMING_FLAY"
    const val HOTSPOT_RADAR = "HOTSPOT_RADAR"
    const val HOTSPOT_RADAR_NAME = "Hotspot Radar"
}
