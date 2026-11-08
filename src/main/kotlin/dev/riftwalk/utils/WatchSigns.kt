package dev.riftwalk.utils

/**
 * Signs that someone is paying attention to you (pure text / maths, unit tested).
 * Automatic features use these to stop and tell you; what to do after that is up to you.
 */
object WatchSigns {
    val DEFAULT_STAFF_TAGS = listOf("ADMIN", "OWNER", "MOD", "HELPER", "GM", "YOUTUBE")

    private val COLOR = Regex("§.")
    private val PARTY_INVITE = Regex("^(?:\\[[^]]+] )?([A-Za-z0-9_]{1,16}) has invited you to join (?:their|[A-Za-z0-9_]{1,16}'s) party!?", RegexOption.IGNORE_CASE)
    private val PRIVATE_MESSAGE = Regex("^From (?:\\[[^]]+] )?([A-Za-z0-9_]{1,16}): ")

    /** Who invited you to a party, or null. */
    fun partyInvite(message: String): String? =
        message.split('\n').firstNotNullOfOrNull { PARTY_INVITE.find(clean(it))?.groupValues?.get(1) } // Hypixel wraps it in ----- lines

    /** Who sent you a private message ("From <name>: ..."), or null. */
    fun privateMessage(line: String): String? = PRIVATE_MESSAGE.find(clean(line))?.groupValues?.get(1)

    /** The staff tag ([ADMIN], [MOD] ...) in a name, or null. Only bracketed, whole-word tags count. */
    fun staffTag(name: String, tags: Collection<String> = DEFAULT_STAFF_TAGS): String? {
        val t = clean(name).uppercase()
        return tags.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.firstOrNull { Regex("\\[${Regex.escape(it)}(?:\\+*)]").containsMatchIn(t) }
    }

    /** Tags from a comma separated setting ("ADMIN, MOD, GM"). Empty = the defaults. */
    fun parseTags(setting: String): List<String> =
        setting.split(',', ' ', ';').map { it.trim().trim('[', ']').uppercase() }.filter { it.isNotEmpty() }.ifEmpty { DEFAULT_STAFF_TAGS }

    /** True when going from one position to the next is a jump of at least [limit] blocks. */
    fun teleported(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double, limit: Double = 30.0): Boolean {
        val dx = x1 - x0; val dy = y1 - y0; val dz = z1 - z0
        return dx * dx + dy * dy + dz * dz >= limit * limit
    }

    private fun clean(s: String) = s.replace(COLOR, "").trim()
}
