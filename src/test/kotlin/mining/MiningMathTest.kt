package mining

import dev.riftwalk.features.impl.mining.MiningMath
import dev.riftwalk.features.impl.mining.MiningMath.Candidate
import dev.riftwalk.utils.HumanProfile
import dev.riftwalk.utils.HumanTiming
import java.util.Random
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MiningMathTest {
    private val cands = listOf(
        Candidate(1, 2.0, 10.0, 1),
        Candidate(2, 3.0, 25.0, 1),
        Candidate(3, 3.5, 40.0, 1),
        Candidate(4, 4.0, 80.0, 1),
    )

    @Test
    fun `no mistakes - always the best score`() {
        val rnd = Random(1)
        repeat(1000) { assertEquals(1L, MiningMath.choose(cands, HumanProfile(failRate = 0.0), rnd)!!.key) }
    }

    @Test
    fun `mistakes - the best most of the time, otherwise second or third, never worse`() {
        val rnd = Random(2)
        val picks = List(20_000) { MiningMath.choose(cands, HumanProfile(failRate = 0.1), rnd)!!.key }
        val best = picks.count { it == 1L } / picks.size.toDouble()
        assertTrue(best in 0.87 .. 0.93, "best picked $best")
        assertTrue(picks.none { it == 4L }, "picked the fourth")
    }

    @Test
    fun `same seed gives the same picks`() {
        fun run(seed: Long) = Random(seed).let { r -> List(500) { MiningMath.choose(cands, HumanProfile(failRate = 0.3), r)!!.key } }
        assertEquals(run(9), run(9))
    }

    @Test
    fun `nothing to mine gives null`() = assertNull(MiningMath.choose(emptyList(), HumanProfile(), Random(0)))

    @Test
    fun `a wanted block wins over a slightly easier one`() {
        val titanium = Candidate(10, 3.0, 35.0, MiningMath.TITANIUM.priority)
        val grey = Candidate(11, 2.0, 5.0, MiningMath.MITHRIL_GREY.priority)
        assertEquals(10L, MiningMath.choose(listOf(grey, titanium), HumanProfile(failRate = 0.0), Random(0))!!.key)
    }

    @Test
    fun `turn degrees - straight ahead is zero, to the side is ninety`() {
        assertTrue(MiningMath.turnDegrees(0f, 0f, 0.0, 0.0, 1.0) < 0.01)
        assertTrue(abs(MiningMath.turnDegrees(0f, 0f, 1.0, 0.0, 0.0) - 90.0) < 0.01)
        assertTrue(abs(MiningMath.turnDegrees(170f, 0f, 0.0, 0.0, - 1.0) - 10.0) < 0.01, "wraps around 180")
    }

    @Test
    fun `face point - on the face towards the eye, never near its edges`() {
        val rnd = Random(5)
        repeat(2000) {
            val ex = rnd.nextDouble() * 10 - 5; val ey = rnd.nextDouble() * 10 - 5; val ez = rnd.nextDouble() * 10 - 5
            val p = MiningMath.facePoint(ex, ey, ez, 0, 0, 0, rnd.nextDouble() * 2 - 1, rnd.nextDouble() * 2 - 1)
            val onFace = p.count { abs(it) < 1e-9 || abs(it - 1.0) < 1e-9 }
            assertTrue(onFace >= 1, "not on a face: ${p.toList()}")
            for (c in p) assertTrue(c in - 1e-9 .. 1.0 + 1e-9, "outside the block: ${p.toList()}")
            // the chosen face is the one the eye sees: the eye is on the outer side of it
            val inner = p.count { it in 0.18 - 1e-9 .. 0.82 + 1e-9 }
            assertEquals(2, inner, "two coordinates stay off the edges: ${p.toList()}")
        }
        val top = MiningMath.facePoint(0.5, 5.0, 0.5, 0, 0, 0, 0.0, 0.0)
        assertEquals(1.0, top[1], 1e-9)
    }

    @Test
    fun `gemstones are glass and panes, one kind per colour`() {
        val ruby = MiningMath.GEMSTONES.first { it.name == "Ruby" }
        assertEquals(setOf("red_stained_glass", "red_stained_glass_pane"), ruby.ids)
        assertEquals(MiningMath.GEMSTONES.size, MiningMath.GEMSTONES.flatMap { it.ids }.toSet().size / 2)
    }

    @Test
    fun `give up - the floor until there are samples, then three medians within the cap`() {
        assertEquals(120, MiningMath.giveUpTicks(listOf(10, 12), 120, 600))
        assertEquals(120, MiningMath.giveUpTicks(List(9) { 10 }, 120, 600), "fast blocks keep the floor")
        assertEquals(300, MiningMath.giveUpTicks(List(9) { 100 }, 120, 600), "slow blocks get 3x their median")
        assertEquals(600, MiningMath.giveUpTicks(List(9) { 400 }, 120, 600), "never past the cap")
    }

    @Test
    fun `median of samples`() {
        assertEquals(- 1, MiningMath.median(emptyList()))
        assertEquals(5, MiningMath.median(listOf(9, 1, 5)))
    }

    @Test
    fun `on ray - in front and close to the line only`() {
        // looking along +z from the origin
        assertTrue(MiningMath.onRay(0.0, 0.0, 0.0, 0.0, 0.0, 2.0, 0.2, 0.1, 3.0, 5.0, 0.6))
        assertTrue(! MiningMath.onRay(0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, - 3.0, 5.0, 0.6), "behind")
        assertTrue(! MiningMath.onRay(0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 7.0, 5.0, 0.6), "too far")
        assertTrue(! MiningMath.onRay(0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 1.5, 0.0, 3.0, 5.0, 0.6), "off to the side")
        assertTrue(! MiningMath.onRay(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 5.0, 0.6), "no look vector")
    }

    @Test
    fun `per hour waits for a minute of data`() {
        assertEquals(0, MiningMath.perHour(10, 30_000))
        assertEquals(600, MiningMath.perHour(10, 60_000))
    }

    // the wait between one block breaking and going for the next comes from HumanTiming with Auto Mining's
    // default range (Switch Delay 1..14 ticks): long tail, inside the range, same seed same waits
    private fun waits(seed: Long) = HumanTiming(seed) { 0L }.apply(HumanProfile()).let { t -> IntArray(20_000) { if (it % 20 == 0) t.tick(); t.next(1, 14) } }

    @Test
    fun `switch delay - P99 more than twice the median`() {
        val s = waits(3).sortedArray()
        val med = s[s.size / 2]; val p99 = s[(s.size * 0.99).toInt()]
        assertTrue(p99 > 2 * med, "median $med P99 $p99")
    }

    @Test
    fun `switch delay - inside the range`() {
        val s = waits(4)
        assertTrue(s.min() >= 1 && s.max() <= 14, "${s.min()}..${s.max()}")
    }

    @Test
    fun `switch delay - same seed same waits`() = assertTrue(waits(8).contentEquals(waits(8)))
}
