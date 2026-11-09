package human

import dev.marceline.utils.HumanMotion
import dev.marceline.utils.TurnConfig
import dev.marceline.utils.TurnEngine
import java.util.Random
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TurnEngineTest {
    private val cfg = TurnConfig(snap = false)

    /** Ticks of: settle at the tick start, one turn step, four frames. Returns the view after each tick. */
    private fun run(engine: TurnEngine, v: FakeView, yaw: Float, pitch: Float, ticks: Int): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>()
        for (t in 0L until ticks) {
            engine.settle(v)
            engine.turn(v, yaw, pitch, 1.5f, true, t)
            for (f in 1 .. 4) engine.frame(v, t, f / 4f)
            out += v.yaw to v.pitch
        }
        return out
    }

    @Test
    fun `reaches the target and every write goes through the view`() {
        val v = FakeView()
        val e = TurnEngine(Random(1)) { cfg }
        run(e, v, 120f, 20f, 200)
        assertTrue(abs(HumanMotion.wrapDeg(v.yaw - 120f)) < 2f && abs(v.pitch - 20f) < 2f, "ended at ${v.yaw} ${v.pitch}")
        assertTrue(v.writes.isNotEmpty())
    }

    @Test
    fun `same seed gives the same path`() {
        val a = run(TurnEngine(Random(7)) { cfg }, FakeView(), - 90f, 30f, 80)
        val b = run(TurnEngine(Random(7)) { cfg }, FakeView(), - 90f, 30f, 80)
        assertEquals(a, b)
    }

    @Test
    fun `mouse grid keeps writes on whole mouse counts`() {
        val c = TurnConfig(snap = true, sensitivity = 0.5)
        val v = FakeView()
        val e = TurnEngine(Random(3)) { c }
        var prev = v.yaw to v.pitch
        val g = HumanMotion.gcd(0.5)
        for (t in 0L until 60) {
            e.settle(v); e.turn(v, 60f, 10f, 1.5f, true, t)
            for (f in 1 .. 4) {
                e.frame(v, t, f / 4f)
                val dy = (v.yaw - prev.first).toDouble()
                assertTrue(abs(dy / g - Math.round(dy / g)) < 1e-3, "yaw change $dy is not a whole count of $g")
                prev = v.yaw to v.pitch
            }
        }
    }
}
