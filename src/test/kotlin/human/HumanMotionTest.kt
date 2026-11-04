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
}
