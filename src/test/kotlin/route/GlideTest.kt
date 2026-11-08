package route

import dev.riftwalk.utils.HumanMotion
import java.util.Random
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** A tick's turn spread over the frames: no frame moves more than its share, and the frames add up to the turn. */
class GlideTest {
    @Test fun perFrameBoundAndTotal() {
        val r = Random(41)
        repeat(20_000) {
            val from = (r.nextFloat() * 2 - 1) * 720f              // any yaw, also past ±360
            val yaw = (r.nextFloat() * 2 - 1) * 720f
            val to = HumanMotion.glideTarget(from, yaw)
            val turn = to - from
            assertTrue(abs(turn) <= 180.0001f, "took the long way round: $turn")
            // frames: random partial ticks rising to 1
            val frames = 1 + r.nextInt(30)
            val ts = (List(frames - 1) { r.nextFloat() } + 1f).sorted()
            var last = HumanMotion.glideAt(from, to, 0f)
            var lastT = 0f
            var sum = 0.0
            for (t in ts) {
                val now = HumanMotion.glideAt(from, to, t)
                val step = abs(now - last)
                assertTrue(step <= abs(turn) * (t - lastT) + 1e-3f, "frame moved $step for ${t - lastT} of a $turn° turn")
                assertTrue(step <= 180.0001f)
                sum += now - last
                last = now; lastT = t
            }
            assertTrue(abs(sum - turn) < 1e-3, "frames add up to $sum, turn was $turn")
            assertTrue(abs(HumanMotion.wrapDeg(last - yaw)) < 1e-3f, "ended at $last, wanted $yaw")
        }
    }

    @Test fun pitchGlideStaysInRange() {
        val r = Random(43)
        repeat(10_000) {
            val from = (r.nextFloat() * 2 - 1) * 90f
            val to = (r.nextFloat() * 2 - 1) * 90f
            for (i in 0 .. 20) {
                val v = HumanMotion.glideAt(from, to, i / 20f)
                assertTrue(v in -90.0001f .. 90.0001f, "pitch $v left -90..90")
            }
        }
    }
}
