package fishing

import dev.marceline.features.impl.fishing.LavaGuardMath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LavaGuardMathTest {
    @Test
    fun `a knock follows the game's air physics`() {
        val p = LavaGuardMath.path(0.0, 64.0, 0.0, 0.8, 0.4, 0.0, ticks = 3)
        assertEquals(0.8, p[0][0], 1e-9)
        assertEquals(64.4, p[0][1], 1e-9)
        assertEquals(0.8 + 0.8 * 0.91, p[1][0], 1e-9)
        assertEquals(64.4 + (0.4 - 0.08) * 0.98, p[1][1], 1e-9)
        // horizontal travel never exceeds v / (1 - drag)
        val far = LavaGuardMath.path(0.0, 64.0, 0.0, 0.8, 0.0, 0.0, ticks = 200).last()[0]
        assertTrue(far < 0.8 / (1 - LavaGuardMath.DRAG_AIR) + 1e-6)
    }

    @Test
    fun `walking is not a knock, a blow is`() {
        assertFalse(LavaGuardMath.isKnock(0.28, 0.0)) // sprinting
        assertTrue(LavaGuardMath.isKnock(0.5, 0.3))
    }

    // a flat floor at y = 63 for x < 3, lava beyond (floor top at 64)
    private val solid = { x: Int, y: Int, _: Int -> y == 63 && x < 3 }
    private val lava = { x: Int, y: Int, _: Int -> y <= 63 && x >= 3 }

    @Test
    fun `a knock towards the lava lands in it, a short one doesn't`() {
        val big = LavaGuardMath.path(0.5, 64.0, 0.5, 0.9, 0.4, 0.0)
        assertTrue(LavaGuardMath.landsInLava(big, solid, lava) != null)
        val small = LavaGuardMath.path(0.5, 64.0, 0.5, 0.2, 0.4, 0.0)
        assertNull(LavaGuardMath.landsInLava(small, solid, lava))
        // away from the lava: lands on the floor
        val away = LavaGuardMath.path(2.5, 64.0, 0.5, - 0.9, 0.4, 0.0)
        assertNull(LavaGuardMath.landsInLava(away, solid, lava))
    }
}
