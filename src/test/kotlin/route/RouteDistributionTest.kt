package route

import dev.riftwalk.utils.HumanMotion
import java.util.Random
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Waypoint offsets, jump timing and sprint delay: distributions over >= 10 000 samples, fixed seeds. */
class RouteDistributionTest {
    private val n = 20_000

    private fun pct(xs: List<Double>, q: Double) = xs.sorted()[((xs.size - 1) * q).toInt()]

    @Test fun waypointOffsetRangeCentreAndRedraw() {
        for (max in listOf(0.05, 0.12, 0.15, 0.25)) {
            val r = Random(11)
            var prev: DoubleArray? = null
            val xs = ArrayList<Double>()
            var changes = 0.0
            repeat(n) {
                val o = HumanMotion.waypointOffset(max, prev, r)
                assertTrue(o.all { abs(it) <= max }, "outside ±$max: ${o.toList()}")
                prev?.let { p ->
                    assertTrue(! o.contentEquals(p), "redraw gave the same offset")
                    changes += abs(o[0] - p[0]) + abs(o[1] - p[1])
                }
                prev = o
                xs += o[0]; xs += o[1]
            }
            assertTrue(abs(pct(xs, 0.5)) < max * 0.05, "median ${pct(xs, 0.5)} not near 0 (max $max)")
            assertTrue(abs(xs.average()) < max * 0.05, "mean ${xs.average()} not near 0")
            // a redraw really moves it: uniform pairs differ by max*2/3 per axis on average
            assertTrue(changes / (n - 1) > max * 0.9, "redraws barely move: ${changes / (n - 1)}")
        }
    }

    @Test fun jumpTimingTailAndFloor() {
        for ((median, spread) in listOf(1.8 to 0.6, 1.0 to 0.8, 3.0 to 0.5)) {
            val r = Random(23)
            val max = 12
            val xs = List(n) { HumanMotion.lognormalTicks(median, spread, 0, max, r).toDouble() }
            assertTrue(xs.all { it >= 0 && it <= max }, "outside 0..$max")
            val med = pct(xs, 0.5); val p99 = pct(xs, 0.99)
            assertTrue(p99 > 2 * med, "median $med spread $spread: P99 $p99 not > 2x median")
        }
        // a floor above 0 holds too
        val r = Random(5)
        assertTrue(List(n) { HumanMotion.lognormalTicks(1.8, 0.6, 1, 6, r) }.all { it >= 1 })
    }

    @Test fun sprintDelayFixedRangeIntegers() {
        val r = Random(31)
        val xs: List<Int> = List(n) { HumanMotion.between(1, 3, r) }
        assertTrue(xs.all { it in 1 .. 3 }, "outside 1..3")
        assertEquals(setOf(1, 2, 3), xs.toSet(), "not every tick count shows up")
        // roughly even (each 1/3 ± 2 %)
        for (v in 1 .. 3) assertTrue(abs(xs.count { it == v } / n.toDouble() - 1.0 / 3) < 0.02, "uneven at $v")
    }

    @Test fun sameSeedSameSamples() {
        fun run(seed: Long): List<Double> {
            val r = Random(seed)
            return List(n) { HumanMotion.lognormalTicks(1.8, 0.6, 0, 5, r).toDouble() + HumanMotion.between(1, 3, r) + HumanMotion.waypointOffset(0.15, null, r).sum() }
        }
        assertEquals(run(99), run(99))
        assertTrue(run(99) != run(100))
    }
}
