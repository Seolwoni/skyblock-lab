package human

import dev.marceline.utils.HumanMotion
import dev.marceline.utils.HumanProfile
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The pure axis functions: axisStep, pitchCeiling, lagRemaining (the turn itself is tested in AxisTurnTest). */
class AxisIndependenceTest {
    private fun steps(seed: Long, n: Int = 20_000): FloatArray {
        val r = Random(seed)
        var paused = false
        return FloatArray(n) { HumanMotion.axisStep(4f, 0.4, 0.08, r, paused).also { paused = it == 0f } }
    }

    @Test
    fun `axis step - long tail, bounds, same seed same steps`() {
        val s = steps(1)
        val moving = s.filter { it > 0f }.sorted()
        val med = moving[moving.size / 2]
        val p99 = moving[(moving.size * 0.99).toInt()]
        assertTrue(p99 > 2 * med, "median $med P99 $p99")
        assertTrue(s.all { it >= 0f && it <= 4f * kotlin.math.exp(0.4 * 6).toFloat() }, "out of bounds")
        assertTrue(steps(1).contentEquals(steps(1)))
        assertTrue(! steps(1).contentEquals(steps(2)), "another seed should give other steps")
    }

    @Test
    fun `axis step - never two pauses in a row on one axis`() {
        val s = steps(3, 100_000)
        for (i in 1 until s.size) assertTrue(! (s[i] == 0f && s[i - 1] == 0f), "two pauses at $i")
        assertTrue(s.count { it == 0f } > 1000, "pauses still happen")
    }

    @Test
    fun `axis step - two axes from one generator never move in a fixed ratio`() {
        val r = Random(5)
        val ratios = List(200) { HumanMotion.axisStep(3f, 0.4, 0.0, r) / HumanMotion.axisStep(3f, 0.4, 0.0, r) }
        assertTrue(ratios.toSet().size > 190, "ratios repeat: ${ratios.toSet().size}")
    }

    @Test
    fun `pitch ceiling - share of yaw, eased on big up-down turns, none at the edge`() {
        assertEquals(7.5f, HumanMotion.pitchCeiling(10f, 0.75, 20f, false), 1e-4f)
        assertEquals(9f, HumanMotion.pitchCeiling(10f, 0.75, - 70f, false), 1e-4f)
        assertEquals(10f, HumanMotion.pitchCeiling(10f, 0.75, 20f, true), 1e-4f)
        assertEquals(9.5f, HumanMotion.pitchCeiling(10f, 0.95, 70f, false), 1e-4f, "a share above the big-turn one stays")
    }

    @Test
    fun `lag - scaled by turn size, carried, and skipped where it would hurt`() {
        assertEquals(0, HumanMotion.lagRemaining(0, true, 2, 25f, 20f, false), "small turn")
        assertEquals(1, HumanMotion.lagRemaining(0, true, 2, 60f, 20f, false), "half way")
        assertEquals(2, HumanMotion.lagRemaining(0, true, 2, 120f, 20f, false), "big turn")
        assertEquals(1, HumanMotion.lagRemaining(1, true, 5, 120f, 20f, false), "a running lag is carried, not restarted")
        assertEquals(1, HumanMotion.lagRemaining(1, false, 5, 120f, 20f, false))
        assertEquals(0, HumanMotion.lagRemaining(0, true, 2, 120f, 20f, true), "edge")
        assertEquals(0, HumanMotion.lagRemaining(0, true, 2, 120f, 20f, false, allowed = false), "urgent aim")
        assertEquals(0, HumanMotion.lagRemaining(0, true, 2, 120f, 20f, false, tracking = true), "following")
        assertEquals(0, HumanMotion.lagRemaining(0, true, 2, 120f, 1f, false), "pitch hardly moves")
        val by = listOf(0, 2, 5).map { HumanMotion.lagRemaining(0, true, it, 120f, 30f, false) }
        assertTrue(by[0] < by[1] && by[1] < by[2], "not increasing with the setting: $by")
    }

    @Test
    fun `profile - pitch share and lag are range checked`() {
        HumanProfile(pitchShare = 0.5, pitchLag = 0); HumanProfile(pitchShare = 1.0, pitchLag = 5)
        assertFailsWith<IllegalArgumentException> { HumanProfile(pitchShare = 0.4) }
        assertFailsWith<IllegalArgumentException> { HumanProfile(pitchLag = 6) }
    }
}
