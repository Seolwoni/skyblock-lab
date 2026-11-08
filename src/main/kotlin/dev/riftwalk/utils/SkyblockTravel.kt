package dev.riftwalk.utils

/**
 * Teleport items and their costs, in one place (next to [SkyblockText]). Values follow the server's plugins;
 * change them here if your server differs.
 */
object SkyblockTravel {
    // ---- item IDs
    const val AOTE = "ASPECT_OF_THE_END"
    const val AOTV = "ASPECT_OF_THE_VOID"
    /** Wither blades blink like an AOTE when they have Wither Impact (Implosion / Shadow Warp / Wither Shield scrolls). */
    val BLINK_BLADES = SkyblockText.WITHER_BLADES
    /** The etherwarp upgrade applied to an AOTV (item attribute / lore marker). */
    const val ETHERWARP_CONDUIT = "ETHERWARP_CONDUIT"
    const val ETHERWARP_LORE = "Ether Transmission"

    // ---- ranges (blocks)
    const val BLINK_RANGE = 8
    const val ETHERWARP_RANGE = 61

    // ---- costs
    const val AOTE_MANA = 50
    const val AOTV_MANA = 45
    const val HYPERION_MANA = 300
    const val ETHERWARP_MANA = 45
    const val ETHERWARP_SOULFLOW = 1

    private val SOULFLOW = Regex("Soulflow:\\s*([\\d,]+)")

    /** Soulflow from tab list lines ("Soulflow: 1,234", colour codes allowed), or null when no line shows it. */
    fun soulflowFrom(lines: Sequence<String>): Int? =
        lines.map { it.replace(Regex("§."), "") }.firstNotNullOfOrNull { SOULFLOW.find(it)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() }

    // ---- landing
    /** Air blocks needed at the landing (feet + head). */
    const val LANDING_AIR = 2
}
