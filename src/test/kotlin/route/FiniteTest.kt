package route

import com.github.noamm9.utils.HumanMotion
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/** Every setting at its edges: nothing comes out NaN or infinite. Ranges are the Human Behaviour / fishing sliders'. */
class FiniteTest {
    private val r = Random(53)
    private val reps = 300

    private fun ok(v: Double, what: String) = assertTrue(v.isFinite(), "$what gave $v")
    private fun ok(v: Float, what: String) = assertTrue(v.isFinite(), "$what gave $v")

    @Test fun motionAtTheEdges() {
        val angles = listOf(0f, 0.001f, -0.001f, 179.999f, 180f, -180f, 360f, 720f, -720f, 1e6f, -1e6f)
        for (sens in listOf(0.0, 0.5, 1.0, -1.0, 2.0)) for (d in angles) repeat(3) {
            ok(HumanMotion.gcdSnap(d.toDouble(), sens), "gcdSnap($d, $sens)")
            ok(HumanMotion.gcd(sens), "gcd($sens)")
        }
        for (step in listOf(0.05f, 0.2f, 1f, 4f)) for (bound in listOf(0f, 0.5f, 3f, 8f)) {
            var v = 0f
            repeat(reps) { v = HumanMotion.walk(v, step, bound, r); ok(v, "walk($step, $bound)") }
        }
        for (dist in angles.map { kotlin.math.abs(it) }) for (lo in listOf(0f, 0.1f, 0.5f)) for (hi in listOf(0f, 0.3f, 0.5f)) repeat(5) {
            ok(HumanMotion.overshoot(dist, lo, hi, r), "overshoot($dist, $lo, $hi)")
        }
        for (base in listOf(0f, 0.6f, 14f, 1000f)) for (spread in listOf(0.0, 0.4, 1.0)) for (pause in listOf(0.0, 0.08, 0.3)) repeat(reps / 10) {
            ok(HumanMotion.turnStep(base, spread, pause, r), "turnStep($base, $spread, $pause)")
        }
        for (m in listOf(0.0, 0.12, 0.45, 5.0)) repeat(reps) { HumanMotion.faceOffset(m, r).forEach { ok(it, "faceOffset($m)") } }
    }

    @Test fun timingAtTheEdges() {
        for (sigma in listOf(0.0, 0.6, 3.0)) for (carry in listOf(0.0, 0.3, 0.9, 0.99, 1.0)) {
            var v = 0.0
            repeat(reps) { v = HumanMotion.carried(v, sigma, carry, r); ok(v, "carried($sigma, $carry)") }
        }
        for (median in listOf(0.0, 1.0, 4.0, 20.0, 30.0)) for (spread in listOf(0.0, 0.45, 1.0)) {
            var last = -1
            repeat(reps) { last = HumanMotion.clickInterval(median, spread, r, last); assertTrue(last >= 1, "clickInterval($median, $spread) = $last") }
        }
        for (median in listOf(0.0, 1.8, 6.0)) for (spread in listOf(0.0, 0.6, 1.2)) for (max in listOf(0, 5, 10)) repeat(reps / 10) {
            val t = HumanMotion.lognormalTicks(median, spread, 0, max, r)
            assertTrue(t in 0 .. max, "lognormalTicks($median, $spread, 0, $max) = $t")
        }
        for (base in listOf(0, 1, 2, 10, 40)) for (spread in listOf(0.0, 0.3, 0.8, 1.0)) repeat(reps / 10) {
            assertTrue(HumanMotion.jittered(base, spread, r) >= 1)
        }
        for ((a, b) in listOf(0 to 0, 0 to 6, 1 to 3, 3 to 1, 6 to 10, 10 to 0)) repeat(reps / 10) {
            val t = HumanMotion.between(a, b, r)
            assertTrue(t in minOf(a, b) .. maxOf(a, b))
        }
    }

    @Test fun arrivalAndGlideAtTheEdges() {
        for (radius in listOf(0.0, 0.1, kotlin.math.sqrt(0.12), 0.55, 1.2, 6.0)) {
            ok(HumanMotion.offsetCap(radius), "offsetCap($radius)")
            for (max in listOf(0.0, 0.15, 0.25)) repeat(reps / 10) {
                val o = HumanMotion.waypointOffset(minOf(max, HumanMotion.offsetCap(radius)), null, r)
                o.forEach { ok(it, "waypointOffset") }
                HumanMotion.arrived(0.0, 0.0, o[0], o[1], radius) // must not throw
                HumanMotion.arrived(radius, -radius, o[0], o[1], radius)
            }
        }
        val angles = listOf(0f, 180f, -180f, 359.9f, 1e6f, -1e6f, 1e9f)
        for (a in angles) for (b in angles) {
            ok(HumanMotion.wrapDeg(a - b), "wrapDeg(${a - b})")
            val to = HumanMotion.glideTarget(a, b)
            ok(to, "glideTarget($a, $b)")
            for (t in listOf(0f, 0.5f, 1f)) ok(HumanMotion.glideAt(a, to, t), "glideAt($a, $to, $t)")
        }
    }
}
