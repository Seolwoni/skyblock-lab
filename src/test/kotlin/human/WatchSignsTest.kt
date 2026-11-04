package human

import com.github.noamm9.utils.WatchSigns
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchSignsTest {
    @Test fun partyInvites() {
        assertEquals("Steve", WatchSigns.partyInvite("[MVP+] Steve has invited you to join their party!"))
        assertEquals("alex_2", WatchSigns.partyInvite("§9alex_2 has invited you to join their party!"))
        assertEquals("Bob", WatchSigns.partyInvite("Bob has invited you to join Carl's party!"))
        assertNull(WatchSigns.partyInvite("[VIP] Steve: has invited you to join their party!"))
        assertNull(WatchSigns.partyInvite("You have 60 seconds to accept. Click here to join!"))
    }

    @Test fun privateMessages() {
        assertEquals("Steve", WatchSigns.privateMessage("From [ADMIN] Steve: hello?"))
        assertEquals("bob", WatchSigns.privateMessage("§dFrom §7bob§7: are you there"))
        assertNull(WatchSigns.privateMessage("To Steve: hi"))
        assertNull(WatchSigns.privateMessage("[VIP] Steve: From Bob: hi"))
        assertNull(WatchSigns.privateMessage("Fromage: tasty"))
    }

    @Test fun staffTags() {
        assertEquals("ADMIN", WatchSigns.staffTag("§c[ADMIN] Steve"))
        assertEquals("MOD", WatchSigns.staffTag("[MOD] Bob"))
        assertEquals("YOUTUBE", WatchSigns.staffTag("§c[§fYOUTUBE§c] Creator"))
        assertEquals("HELPER", WatchSigns.staffTag("[helper] x"))
        assertNull(WatchSigns.staffTag("[MVP++] Steve"))
        assertNull(WatchSigns.staffTag("MODERATE Steve"))
        assertNull(WatchSigns.staffTag("[MODERN] Steve"))
        assertNull(WatchSigns.staffTag("Admin Steve"))
        assertEquals("GM", WatchSigns.staffTag("[GM] x", WatchSigns.parseTags(" gm , [mod]")))
        assertEquals(WatchSigns.DEFAULT_STAFF_TAGS, WatchSigns.parseTags("  "))
    }

    @Test fun teleports() {
        assertTrue(WatchSigns.teleported(0.0, 64.0, 0.0, 30.0, 64.0, 0.0))
        assertTrue(WatchSigns.teleported(0.0, 64.0, 0.0, 20.0, 64.0, 23.0))
        assertFalse(WatchSigns.teleported(0.0, 64.0, 0.0, 29.9, 64.0, 0.0))
        assertFalse(WatchSigns.teleported(0.0, 64.0, 0.0, 12.0, 70.0, 12.0, limit = 30.0))
    }
}
