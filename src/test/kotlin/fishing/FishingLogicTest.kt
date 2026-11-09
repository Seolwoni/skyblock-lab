package fishing

import dev.marceline.features.impl.fishing.FishingMath
import dev.marceline.utils.SkyblockText
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Locks down the fishing maths and the server text tables, so later changes can't quietly break them. */
class FishingLogicTest {
    // ---- throw

    @Test
    fun `a level throw lands a few blocks out on water just below`() {
        val l = assertNotNull(FishingMath.landingOnPlane(0.0, 64.0, 0.0, 0f, 0f, 62.5))
        val d = hypot(l[0], l[2])
        assertTrue(d in 3.0 .. 12.0, "landed $d blocks away")
    }

    @Test
    fun `yaw 0 throws towards +z and yaw 90 towards -x`() {
        val south = assertNotNull(FishingMath.landingOnPlane(0.0, 64.0, 0.0, 0f, - 10f, 62.0))
        assertTrue(south[2] > 2.0 && kotlin.math.abs(south[0]) < 0.01)
        val west = assertNotNull(FishingMath.landingOnPlane(0.0, 64.0, 0.0, 90f, - 10f, 62.0))
        assertTrue(west[0] < - 2.0 && kotlin.math.abs(west[2]) < 0.01)
    }

    @Test
    fun `aiming higher (up to a point) throws further`() {
        fun dist(pitch: Float) = FishingMath.landingOnPlane(0.0, 64.0, 0.0, 0f, pitch, 62.0)!![2]
        assertTrue(dist(- 20f) > dist(0f))
        assertTrue(dist(0f) > dist(30f))
    }

    @Test
    fun `the throw speed matches vanilla`() {
        val t = FishingMath.throwStart(0f, 0f)
        val speed = kotlin.math.sqrt(t.vx * t.vx + t.vy * t.vy + t.vz * t.vz)
        assertEquals(1.1, speed, 1e-9) // 0.6 / 1 + 0.5 for a unit look vector
    }

    // ---- radar

    @Test
    fun `two radar lines cross where the hotspot is`() {
        // hotspot at (30, 40): one reading from the origin, one from (60, 0)
        val a = norm(30.0, 40.0)
        val b = norm(30.0 - 60.0, 40.0)
        val c = assertNotNull(FishingMath.cross(0.0, 0.0, a[0], a[1], 60.0, 0.0, b[0], b[1]))
        assertEquals(30.0, c[0], 1e-6)
        assertEquals(40.0, c[1], 1e-6)
    }

    @Test
    fun `parallel or backwards readings give no crossing`() {
        assertNull(FishingMath.cross(0.0, 0.0, 1.0, 0.0, 0.0, 5.0, 1.0, 0.0))
        val a = norm(1.0, 1.0)
        val b = norm(- 1.0, 1.0)
        assertNull(FishingMath.cross(0.0, 0.0, - a[0], - a[1], 10.0, 0.0, b[0], b[1]))
    }

    // ---- server texts

    @Test
    fun `catch lines name the creature even when the text does not`() {
        assertEquals("Yeti", SkyblockText.creatureFromCatch("§c§lWhat is this creature!?"))
        assertEquals("Grim Reaper", SkyblockText.creatureFromCatch("This can't be! The manifestation of death himself!"))
        assertEquals("Sea Walker", SkyblockText.creatureFromCatch("DOUBLE HOOK! You caught a Sea Walker."))
        assertNull(SkyblockText.creatureFromCatch("You caught a Raw Fish!"))
    }

    @Test
    fun `the longest catch line wins`() {
        assertEquals("Poisoned Water Worm", SkyblockText.creatureFromCatch("A Poisoned Water Worm surfaces!"))
        assertEquals("Bloated Mithril Grubber", SkyblockText.creatureFromCatch("A leech of the mines surfaces... you've caught a Bloated Mithril Grubber."))
    }

    @Test
    fun `waiting marker matches question marks and countdowns only`() {
        for (s in listOf("?", "1.2", "3", "4.5s")) assertTrue(SkyblockText.WAITING_MARKER.matches(s), s)
        for (s in listOf("!!!", "HOTSPOT", "Sea Walker", "")) assertTrue(! SkyblockText.WAITING_MARKER.matches(s), s)
    }

    @Test
    fun `mob tags parse name, health and death`() {
        val tag = "[Lv45] Sea Walker 1,200/1,500❤"
        assertEquals("Sea Walker", SkyblockText.MOB_TAG.find(tag)?.groupValues?.get(1))
        val hp = assertNotNull(SkyblockText.MOB_HEALTH.find(tag))
        assertEquals("1,200", hp.groupValues[1])
        assertTrue(SkyblockText.MOB_DEAD.containsMatchIn("Sea Walker 0/1,500❤"))
        assertTrue(! SkyblockText.MOB_DEAD.containsMatchIn(tag))
    }

    private fun norm(x: Double, z: Double): DoubleArray { val l = hypot(x, z); return doubleArrayOf(x / l, z / l) }
}
