package human

import dev.riftwalk.utils.HumanProfile
import dev.riftwalk.utils.HumanTiming
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The delay model must look like people: skewed, with a real tail, never stuck, never out of range. */
class HumanTimingTest {
    private fun sample(n: Int = 20_000, min: Int = 0, max: Int = 40, seed: Long = 7, setup: HumanTiming.() -> Unit = {}): IntArray {
        val t = HumanTiming(seed) { 0L }.apply(setup)
        return IntArray(n) { if (it % 20 == 0) t.tick(); t.next(min, max) }.also { it.sort() }
    }

    private fun IntArray.pct(p: Double) = this[((size - 1) * p).toInt()]

    @Test
    fun `long tail - P99 is more than twice the median`() {
        val s = sample()
        assertTrue(s.pct(0.99) > 2 * s.pct(0.5), "median ${s.pct(0.5)}, P99 ${s.pct(0.99)}")
    }

    @Test
    fun `skewed - the median sits in the lower half of the range`() {
        val s = sample()
        assertTrue(s.pct(0.5) in 6 .. 18, "median ${s.pct(0.5)}")
        assertTrue(s.average() > s.pct(0.5), "mean ${s.average()} should be above the median (right tail)")
    }

    @Test
    fun `always inside the asked range`() {
        val s = sample(min = 5, max = 23)
        assertTrue(s.first() >= 5 && s.last() <= 23, "${s.first()}..${s.last()}")
    }

    @Test
    fun `never the same tick count twice in a row`() {
        val t = HumanTiming(3) { 0L }
        var last = - 1
        repeat(20_000) {
            val v = t.next(2, 12)
            assertTrue(v != last, "repeat of $v")
            last = v
        }
    }

    @Test
    fun `not a flat dice roll - most values are below the middle`() {
        val s = sample()
        val belowMid = s.count { it < 20 } / s.size.toDouble()
        assertTrue(belowMid > 0.7, "only ${(belowMid * 100).toInt()}% below the middle")
    }

    @Test
    fun `a bigger spread gives a longer tail`() {
        val narrow = sample { spread = 0.3; slipChance = 0.0 }
        val wide = sample { spread = 0.9; slipChance = 0.0 }
        assertTrue(wide.pct(0.95) > narrow.pct(0.95), "narrow P95 ${narrow.pct(0.95)}, wide P95 ${wide.pct(0.95)}")
    }

    @Test
    fun `fatigue slows reactions after hours`() {
        var now = 0L
        val fresh = HumanTiming(11) { now }.apply { fatiguePerHour = 0.3 }
        val a = IntArray(5000) { fresh.next(0, 60) }.average()
        now = 2 * 3_600_000L
        val b = IntArray(5000) { fresh.next(0, 60) }.average()
        assertTrue(b > a * 1.15, "fresh $a, after 2 h $b")
    }

    @Test
    fun `failure rate follows the profile`() {
        val t = HumanTiming(5)
        val n = 100_000
        val fails = (0 until n).count { t.fails(0.05) }
        assertTrue(fails / n.toDouble() in 0.04 .. 0.06, "fails $fails")
        assertTrue((0 until n).none { t.fails(0.0) })
    }

    @Test
    fun `profiles reject nonsense`() {
        assertFailsWith<IllegalArgumentException> { HumanProfile(failRate = 1.5) }
        assertFailsWith<IllegalArgumentException> { HumanProfile(delaySpread = 0.0) }
    }

    @Test
    fun `reel tail - a bigger reel spread gives a heavier tail on the reel range`() {
        val normal = sample(min = 1, max = 30) { spread = 0.55 }
        val heavy = sample(min = 1, max = 30) { spread = 0.9 }
        assertTrue(heavy.pct(0.99) > 2 * heavy.pct(0.5), "median ${heavy.pct(0.5)}, P99 ${heavy.pct(0.99)}")
        assertTrue(heavy.pct(0.95) > normal.pct(0.95), "P95 ${heavy.pct(0.95)} vs ${normal.pct(0.95)}")
    }
}
