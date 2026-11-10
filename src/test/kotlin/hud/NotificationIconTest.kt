package hud

import dev.marceline.ui.notification.NotificationIcon
import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationIconTest {
    @Test
    fun titlesPickTheirIcon() {
        assertEquals(NotificationIcon.WARN, NotificationIcon.of("Auto Fishing stopped", "the server moved you"))
        assertEquals(NotificationIcon.STAR, NotificationIcon.of("Rare sea creature!", "§6Thunder §fcame up"))
        assertEquals(NotificationIcon.STAR, NotificationIcon.of("Golden Fish!"))
        assertEquals(NotificationIcon.COIN, NotificationIcon.of("Sell List", "§fCleared"))
        assertEquals(NotificationIcon.FISH, NotificationIcon.of("Bait changed"))
        assertEquals(NotificationIcon.WARN, NotificationIcon.of("Low bait", "§f3 §7left"))
        assertEquals(NotificationIcon.CHECK, NotificationIcon.of("§aProtection Added"))
    }

    @Test
    fun theMessageDecidesWhenTheTitleDoesNot() {
        assertEquals(NotificationIcon.WARN, NotificationIcon.of("Marceline", "§cEverything off §7(12 features)"))
        assertEquals(NotificationIcon.CHECK, NotificationIcon.of("Marceline", "§aBack on §7(12 features)"))
        assertEquals(NotificationIcon.BAT, NotificationIcon.of("Marceline", "§7Nothing is on"))
    }
}
