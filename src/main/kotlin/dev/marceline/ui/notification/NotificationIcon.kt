package dev.marceline.ui.notification

/** Which small icon a toast carries, read from its title (and message) so callers need not say. */
enum class NotificationIcon(val texture: String) {
    BAT("bat"), WARN("warn"), COIN("coin"), CHECK("check"), STAR("star"), FISH("fish");

    companion object {
        private val words = listOf(
            WARN to listOf("stopped", "blocked", "error", "disabled", "removed", "restart", "reboot", "despawn", "low ", "everything off", "wait"),
            STAR to listOf("rare", "golden", "goal"),
            COIN to listOf("sell", "profit", "coin", "chest", "bazaar"),
            CHECK to listOf("added", "copied", "back on", "saved", "reset", "done", "cleared"),
            FISH to listOf("fish", "sea creature", "bait"),
        )

        fun of(title: String, message: String = ""): NotificationIcon {
            val t = title.replace(Regex("§."), "").lowercase()
            val m = message.replace(Regex("§."), "").lowercase()
            for ((icon, keys) in words) if (keys.any { it in t }) return icon
            for ((icon, keys) in words) if (keys.any { it in m }) return icon
            return BAT
        }
    }
}
