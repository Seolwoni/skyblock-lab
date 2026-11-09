package human

import dev.marceline.utils.HumanMotion
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

    // ---- seed reproducibility: the same seed gives the same sequence

    private fun run(seed: Long): List<Double> {
        val r = Random(seed)
        val out = ArrayList<Double>()
        var w = 0f; var c = 0.0; var last = -1
        repeat(500) {
            w = HumanMotion.walk(w, 0.5f, 3f, r); out += w.toDouble()
            out += HumanMotion.overshoot(40f, 0.1f, 0.3f, r).toDouble()
            out += HumanMotion.faceOffset(0.12, r).toList()
            out += HumanMotion.turnStep(4f, 0.4, 0.08, r).toDouble()
            c = HumanMotion.carried(c, 0.6, 0.3, r); out += c
            last = HumanMotion.clickInterval(5.0, 0.45, r, last); out += last.toDouble()
            out += HumanMotion.between(3, 9, r).toDouble()
            out += if (HumanMotion.chance(5.0, r)) 1.0 else 0.0
            out += HumanMotion.lognormalTicks(1.5, 0.6, 0, 5, r).toDouble()
            out += HumanMotion.jittered(10, 0.3, r).toDouble()
            out += HumanMotion.waypointOffset(0.15, null, r).toList()
        }
        return out
    }

    @Test fun sameSeedSameSequence() {
        assertEquals(run(1234), run(1234))
        assertTrue(run(1234) != run(1235))
    }

    // ---- movement distributions

    private fun pctI(xs: List<Int>, q: Double) = xs.sorted()[((xs.size - 1) * q).toInt()]

    @Test fun waypointOffsetDistribution() {
        var prev: DoubleArray? = null
        val xs = ArrayList<Double>()
        repeat(40_000) {
            val o = HumanMotion.waypointOffset(0.15, prev, rnd)
            assertTrue(prev == null || ! o.contentEquals(prev!!), "same offset twice in a row")
            prev = o
            xs += o[0]; xs += o[1]
        }
        assertTrue(xs.all { it >= -0.15 && it <= 0.15 }, "outside ±0.15")
        assertEquals(0.0, pct(xs, 0.5), 0.005)                  // median at the centre
        assertEquals(0.147, pct(xs, 0.99), 0.003)               // uniform: P99 = 0.98 x 0.15
        assertTrue(pct(xs, 0.0) < -0.149 && pct(xs, 1.0) > 0.149) // reaches both ends
        assertTrue(xs.toSet().size > 70_000, "values repeat")
    }

    @Test fun offsetArrivalIsInsideTheOriginalCircle() {
        // every point counted as arrived with an offset was arrived without one (same arrival coordinates)
        for (radius in listOf(0.55, kotlin.math.sqrt(0.12), 1.2, 2.5)) {
            val cap = minOf(0.15, HumanMotion.offsetCap(radius))
            repeat(20_000) {
                val o = HumanMotion.waypointOffset(cap, null, rnd)
                val dx = (rnd.nextDouble() * 2 - 1) * radius * 1.5
                val dz = (rnd.nextDouble() * 2 - 1) * radius * 1.5
                if (HumanMotion.arrived(dx, dz, o[0], o[1], radius)) assertTrue(dx * dx + dz * dz < radius * radius, "r=$radius")
                assertTrue(HumanMotion.arrived(0.0, 0.0, o[0], o[1], radius), "centre must still arrive (r=$radius)")
            }
        }
        // puzzle steps: arrival never in a neighbouring block
        val r = kotlin.math.sqrt(0.12)
        val cap = minOf(0.15, HumanMotion.offsetCap(r))
        assertEquals(0.1225, cap, 0.0005)
        repeat(20_000) {
            val o = HumanMotion.waypointOffset(cap, null, rnd)
            val dx = rnd.nextDouble() - 0.5; val dz = rnd.nextDouble() - 0.5
            if (HumanMotion.arrived(dx * 2, dz * 2, o[0], o[1], r)) assertTrue(abs(dx * 2) < 0.5 && abs(dz * 2) < 0.5)
        }
    }

    @Test fun jumpTimingDistribution() {
        val xs = List(50_000) { HumanMotion.lognormalTicks(1.8, 0.6, 0, 5, rnd) }
        assertTrue(xs.all { it in 0 .. 5 }, "outside 0-5")
        assertEquals(2, pctI(xs, 0.5))                          // median 1.8 ticks rounds to 2
        assertTrue(pctI(xs, 0.99) > 2 * pctI(xs, 0.5), "P99 ${pctI(xs, 0.99)} median ${pctI(xs, 0.5)}")
        assertEquals(0, xs.minOrNull()); assertEquals(5, xs.maxOrNull())
    }

    @Test fun sprintDelayDistribution() {
        val xs = List(30_000) { HumanMotion.between(1, 3, rnd) }
        assertTrue(xs.all { it in 1 .. 3 })
        assertEquals(2, pctI(xs, 0.5))
        assertEquals(3, pctI(xs, 0.99))
        assertEquals(1, xs.minOrNull()); assertEquals(3, xs.maxOrNull())
        val c = IntArray(4); xs.forEach { c[it]++ }
        assertTrue((1 .. 3).all { c[it] in 9_400 .. 10_600 }, c.joinToString())
    }

    @Test fun repathIntervalSpread() {
        val xs = List(30_000) { HumanMotion.jittered(10, 0.3, rnd) }
        assertTrue(xs.all { it in 7 .. 13 }, "outside ±30%")
        assertEquals(10, pctI(xs, 0.5))
        assertEquals(13, pctI(xs, 0.99))
        assertTrue(xs.toSet().size == 7)
    }
}
