package human

import com.github.noamm9.utils.HumanMotion
import java.util.Random
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HumanMotionTest {
    private val rnd = Random(42)

    @Test fun walkStaysInsideBound() {
        var v = 0f
        repeat(50_000) {
            v = HumanMotion.walk(v, 0.8f, 3f, rnd)
            assertTrue(abs(v) <= 3f, "walk left the range: $v")
        }
    }

    @Test fun walkCoversTheRangeAndCentresOnZero() {
        var v = 0f
        var sum = 0.0
        var hiSeen = 0f
        val n = 100_000
        repeat(n) { v = HumanMotion.walk(v, 0.6f, 3f, rnd); sum += v; hiSeen = maxOf(hiSeen, abs(v)) }
        assertTrue(hiSeen > 2.5f, "never got near the edge: $hiSeen")
        assertTrue(abs(sum / n) < 0.3, "drifts off-centre: ${sum / n}")
    }

    @Test fun walkIsCorrelated() {
        // neighbouring values are close (a walk, not a fresh pick each time)
        var v = 0f
        var bigJumps = 0
        repeat(10_000) {
            val n = HumanMotion.walk(v, 0.4f, 3f, rnd)
            if (abs(n - v) > 2f) bigJumps++
            v = n
        }
        assertTrue(bigJumps < 20, "too many jumps: $bigJumps")
    }

    @Test fun overshootShareAndCap() {
        val xs = List(20_000) { HumanMotion.overshoot(60f, 0.1f, 0.3f, rnd) }
        assertTrue(xs.all { it in 1f .. 3f }, "outside 0.1-0.3 x 10°")
        assertEquals(2.0, xs.average(), 0.05)
        assertEquals(0f, HumanMotion.overshoot(15f, 0.1f, 0.3f, rnd))
        assertEquals(0f, HumanMotion.overshoot(60f, 0f, 0f, rnd))
    }

    @Test fun faceOffsetBounds() {
        repeat(20_000) {
            val o = HumanMotion.faceOffset(0.12, rnd)
            assertTrue(o.all { abs(it) <= 0.12 })
        }
        repeat(1000) { assertTrue(HumanMotion.faceOffset(5.0, rnd).all { abs(it) < 0.5 }) }
    }

    @Test fun chanceMatchesRate() {
        val n = 200_000
        val hits = (0 until n).count { HumanMotion.chance(2.0, rnd) }
        assertEquals(0.02, hits / n.toDouble(), 0.002)
        assertTrue((0 until 1000).none { HumanMotion.chance(0.0, rnd) })
    }

    @Test fun betweenIsUniformAndInclusive() {
        val counts = IntArray(6)
        repeat(60_000) { counts[HumanMotion.between(8, 3, rnd) - 3]++ }
        assertTrue(counts.all { it in 9_000 .. 11_000 }, counts.joinToString())
    }

    // ---- 1. input quantization

    private fun onGrid(v: Double, g: Double) = abs(v / g - Math.round(v / g)) < 1e-6

    @Test fun snapIsAlwaysOnTheGrid() {
        for (sens in listOf(0.0, 0.1, 0.25, 0.5, 0.75, 1.0)) {
            val g = HumanMotion.gcd(sens)
            repeat(5_000) {
                val d = (rnd.nextDouble() * 2 - 1) * 40
                assertTrue(onGrid(HumanMotion.gcdSnap(d, sens), g), "sens $sens d $d")
            }
        }
    }

    @Test fun snapTwiceChangesNothing() {
        repeat(5_000) {
            val sens = rnd.nextDouble()
            val once = HumanMotion.gcdSnap((rnd.nextDouble() * 2 - 1) * 90, sens)
            assertEquals(once, HumanMotion.gcdSnap(once, sens), 1e-9)
        }
    }

    @Test fun gridWidthPerSensitivity() {
        assertEquals(0.0096, HumanMotion.gcd(0.0), 1e-9)          // f = 0.2
        assertEquals(0.15, HumanMotion.gcd(0.5), 1e-9)            // f = 0.5
        assertEquals(0.6144, HumanMotion.gcd(1.0), 1e-9)          // f = 0.8
        assertTrue(HumanMotion.gcd(0.2) < HumanMotion.gcd(0.4))
        // a sub-step move rounds to 0 counts or 1 count, never a fraction
        assertEquals(0.0, HumanMotion.gcdSnap(0.07, 0.5), 1e-9)
        assertEquals(0.15, HumanMotion.gcdSnap(0.08, 0.5), 1e-9)
    }

    // ---- 2. turn trace

    private fun pct(xs: List<Double>, q: Double) = xs.sorted()[((xs.size - 1) * q).toInt()]

    @Test fun turnStepsHaveATailAndPauses() {
        val n = 50_000
        val xs = List(n) { HumanMotion.turnStep(5f, 0.4, 0.08, rnd).toDouble() }
        val zero = xs.count { it == 0.0 } / n.toDouble()
        assertTrue(zero in 0.07 .. 0.09, "pause ratio $zero")
        val moving = xs.filter { it > 0 }
        assertTrue(pct(moving, 0.99) > 2 * pct(moving, 0.5), "P99 ${pct(moving, 0.99)} median ${pct(moving, 0.5)}")
        assertEquals(5.0, pct(moving, 0.5), 0.15)
        // no two moving steps the same in a row
        assertTrue(moving.zipWithNext().none { (a, b) -> a == b })
    }

    @Test fun pauseRatioFollowsTheSetting() {
        for (r in listOf(0.0, 0.03, 0.15)) {
            val z = (0 until 40_000).count { HumanMotion.turnStep(3f, 0.3, r, rnd) == 0f } / 40_000.0
            assertEquals(r, z, 0.01)
        }
    }

    // ---- 3. cast-to-cast variance

    @Test fun castOffsetsCarryOver() {
        var v = 0.0
        val xs = List(100_000) { v = HumanMotion.carried(v, 0.8, 0.3, rnd); v }
        val mean = xs.average()
        val sd = kotlin.math.sqrt(xs.sumOf { (it - mean) * (it - mean) } / xs.size)
        assertEquals(0.8, sd, 0.03)
        val lag1 = xs.zipWithNext().sumOf { (a, b) -> (a - mean) * (b - mean) } / xs.size / (sd * sd)
        assertEquals(0.3, lag1, 0.03)
        assertTrue(xs.zipWithNext().none { (a, b) -> a == b })
    }

    // ---- 4. clicks

    @Test fun clickGapsTailAndNoRepeats() {
        var last = -1
        val xs = List(50_000) { last = HumanMotion.clickInterval(5.0, 0.45, rnd, last); last }
        val d = xs.map { it.toDouble() }
        assertTrue(pct(d, 0.99) > 2 * pct(d, 0.5), "P99 ${pct(d, 0.99)} median ${pct(d, 0.5)}")
        assertTrue(xs.zipWithNext().none { (a, b) -> a == b }, "same gap twice in a row")
        assertTrue(xs.all { it >= 1 })
        assertTrue(xs.toSet().size >= 8, "too few distinct gaps")
    }
}
