package route

import com.github.noamm9.features.impl.fishing.TravelMath
import com.github.noamm9.features.impl.fishing.TravelMath.Move
import com.github.noamm9.utils.PathMath.P
import java.util.Random
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TravelMathTest {
    /** Floor at y=0 (solid), air above, plus whatever the test adds. World height 0..255. */
    private class World : TravelMath.Blocks {
        val solids = HashSet<P>()
        val lavas = HashSet<P>()
        val water = HashSet<P>()
        var floor = true
        override fun solid(p: P) = p in solids || (floor && p.y == 0)
        override fun passable(p: P) = ! solid(p) && p !in lavas && p !in water
        override fun lava(p: P) = p in lavas
        override fun inWorld(y: Int) = y in 0 .. 255
    }

    // ---- landing

    @Test fun landingNeedsTwoAirSolidUnderNoLavaInWorld() {
        val w = World()
        assertTrue(TravelMath.landable(P(5, 1, 5), w))
        w.solids += P(5, 2, 5)
        assertTrue(! TravelMath.landable(P(5, 1, 5), w), "head blocked")
        w.solids -= P(5, 2, 5)
        assertTrue(! TravelMath.landable(P(5, 3, 5), w), "nothing under the feet")
        w.lavas += P(6, 1, 5)
        assertTrue(! TravelMath.landable(P(5, 1, 5), w), "lava next to it")
        w.lavas.clear(); w.lavas += P(4, 0, 4)
        assertTrue(! TravelMath.landable(P(5, 1, 5), w), "lava diagonally under")
        w.lavas.clear()
        assertTrue(! TravelMath.landable(P(5, 0, 5), w), "feet in the floor")
        assertTrue(! TravelMath.landable(P(5, 255, 5), w), "head above the world")
        assertTrue(! TravelMath.landable(P(5, -3, 5), w), "below the world")
    }

    // ---- blink

    @Test fun blinkGoesUpToRangeOnFlatGround() {
        val w = World()
        val east = TravelMath.look(-90.0, 0.0) // +x
        val l = assertNotNull(TravelMath.blinkLanding(0.5, 1.0, 0.5, east, 8, w))
        assertEquals(P(8, 1, 0), l)
    }

    @Test fun blinkStopsBeforeAWall() {
        val w = World()
        for (y in 1 .. 3) w.solids += P(5, y, 0)
        val l = assertNotNull(TravelMath.blinkLanding(0.5, 1.0, 0.5, TravelMath.look(-90.0, 0.0), 8, w))
        assertTrue(l.x < 5, "went through the wall: $l")
        assertTrue(TravelMath.landable(l, w))
    }

    @Test fun blinkNeverLandsInsideOrPastRange() {
        val r = Random(61)
        repeat(3000) {
            val w = World()
            repeat(60) { w.solids += P(r.nextInt(17) - 8, 1 + r.nextInt(4), r.nextInt(17) - 8) }
            repeat(6) { w.lavas += P(r.nextInt(17) - 8, 1, r.nextInt(17) - 8) }
            w.solids -= P(0, 1, 0); w.solids -= P(0, 2, 0); w.lavas -= P(0, 1, 0)
            val yaw = r.nextDouble() * 360 - 180
            val pitch = r.nextDouble() * 60 - 30
            val l = TravelMath.blinkLanding(0.5, 1.0, 0.5, TravelMath.look(yaw, pitch), 8, w) ?: return@repeat
            assertTrue(TravelMath.landable(l, w), "landed somewhere bad: $l")
            assertTrue(sqrt(((l.x + 0.5) - 0.5).let { it * it } + (l.y - 1.0).let { it * it } + ((l.z + 0.5) - 0.5).let { it * it }) <= 8 + 1.8, "past range: $l")
        }
    }

    // ---- etherwarp

    @Test fun etherwarpLandsOnTopOfTheBlockLookedAt() {
        val w = World()
        w.solids += P(20, 0, 0); w.floor = false
        val eye = doubleArrayOf(0.5, 2.62, 0.5)
        val a = TravelMath.aim(eye[0], eye[1], eye[2], 20.5, 1.0, 0.5)
        val l = TravelMath.etherwarpLanding(eye[0], eye[1], eye[2], TravelMath.look(a[0], a[1]), 61, w, a[1])
        assertEquals(P(20, 1, 0), l)
    }

    @Test fun etherwarpRefusals() {
        val w = World(); w.floor = false
        w.solids += P(70, 0, 0)
        val eye = doubleArrayOf(0.5, 2.62, 0.5)
        val far = TravelMath.aim(eye[0], eye[1], eye[2], 70.5, 1.0, 0.5)
        assertNull(TravelMath.etherwarpLanding(eye[0], eye[1], eye[2], TravelMath.look(far[0], far[1]), 61, w, far[1]), "past 61 blocks")
        w.solids += P(10, 0, 0); w.solids += P(10, 1, 0)
        val blocked = TravelMath.aim(eye[0], eye[1], eye[2], 10.5, 0.5, 0.5)
        val l = TravelMath.etherwarpLanding(eye[0], eye[1], eye[2], TravelMath.look(blocked[0], blocked[1]), 61, w, blocked[1])
        assertTrue(l == null || TravelMath.landable(l, w))
        assertNull(TravelMath.etherwarpLanding(eye[0], eye[1], eye[2], TravelMath.look(0.0, 80.0), 61, w, 80.0, pitchMin = -60.0, pitchMax = 60.0), "outside the pitch limits")
        assertNull(TravelMath.etherwarpLanding(eye[0], eye[1], eye[2], TravelMath.look(0.0, -80.0), 61, w, -80.0), "nothing to hit")
    }

    // ---- cost

    @Test fun costAddsTicksManaAndSoulflow() {
        val m = TravelMath.CostModel(walkSpeed = 0.25, teleportTicks = 8.0, manaPerTick = 2.0, manaWeight = 1.0, soulflowWeight = 40.0)
        assertEquals(40.0, TravelMath.cost(Move.WALK, 10.0, 0, 0, m), 1e-9)
        assertEquals(8.0 + 25.0, TravelMath.cost(Move.BLINK, 8.0, 50, 0, m), 1e-9)
        assertEquals(8.0 + 22.5 + 40.0, TravelMath.cost(Move.ETHERWARP, 40.0, 45, 1, m), 1e-9)
        // more mana weight -> teleports cost more, walking doesn't change
        val heavy = m.copy(manaWeight = 3.0)
        assertTrue(TravelMath.cost(Move.BLINK, 8.0, 50, 0, heavy) > TravelMath.cost(Move.BLINK, 8.0, 50, 0, m))
        assertEquals(TravelMath.cost(Move.WALK, 10.0, 0, 0, m), TravelMath.cost(Move.WALK, 10.0, 0, 0, heavy))
        assertTrue(TravelMath.affordable(100, 50, 50)); assertTrue(! TravelMath.affordable(99, 50, 50))
    }

    // ---- smoothing

    @Test fun reaimBetweenTeleportsInARowOnly() {
        val a = P(1, 1, 1); val b = P(9, 1, 1); val c = P(17, 1, 1); val d = P(20, 1, 1)
        val out = TravelMath.smooth(listOf(Move.WALK to a, Move.BLINK to b, Move.BLINK to c, Move.WALK to d, Move.ETHERWARP to a, Move.BLINK to b))
        assertEquals(listOf(Move.WALK, Move.BLINK, null, Move.BLINK, Move.WALK, Move.ETHERWARP, null, Move.BLINK), out.map { it.move })
        assertEquals(b, out[2].to, "re-aim happens where the first blink landed")
        assertEquals(0, TravelMath.smooth(emptyList()).size)
    }

    // ---- human parts

    private fun pct(xs: List<Double>, q: Double) = xs.sorted()[((xs.size - 1) * q).toInt()]

    @Test fun aimErrorAboutOneAndAHalfDegreesP99UnderFour() {
        val r = Random(71)
        val yaw = List(20_000) { abs(TravelMath.aimError(1.5, r)[0]) }
        assertEquals(1.0, pct(yaw, 0.5), 0.1)             // |N(0, 1.5)| median = 0.674 sigma
        assertTrue(pct(yaw, 0.99) in 3.4 .. 4.4, "P99 ${pct(yaw, 0.99)}")
        assertTrue(pct(yaw, 0.99) > 2 * pct(yaw, 0.5))
        val both = List(20_000) { TravelMath.aimError(1.5, r).let { hypot(it[0], it[1]) } }
        assertTrue(both.all { it.isFinite() })
        assertEquals(0.0, TravelMath.aimError(0.0, r).sum())
    }

    @Test fun blinkGapsMedianThreeTailNoTriple() {
        val r = Random(73)
        var a = -1; var b = -1
        val xs = ArrayList<Int>()
        repeat(20_000) { val t = TravelMath.blinkGap(3.0, 0.5, 1, 12, a, b, r); xs += t; b = a; a = t }
        val d = xs.map { it.toDouble() }
        assertEquals(3.0, pct(d, 0.5), 0.01)
        assertTrue(pct(d, 0.99) > 2 * pct(d, 0.5), "P99 ${pct(d, 0.99)}")
        assertTrue(xs.all { it in 1 .. 12 })
        assertTrue(xs.windowed(3).none { it[0] == it[1] && it[1] == it[2] }, "same gap three times running")
        assertEquals(4, TravelMath.blinkGap(3.0, 0.5, 4, 4, 4, 4, r), "min == max stays put")
    }

    @Test fun nothingNonFiniteAtTheEdges() {
        val r = Random(79)
        val w = World()
        for (yaw in listOf(-180.0, 0.0, 90.0, 180.0, 1e6)) for (pitch in listOf(-90.0, 0.0, 90.0)) {
            val v = TravelMath.look(yaw, pitch)
            assertTrue(v.all { it.isFinite() })
            TravelMath.blinkLanding(0.5, 1.0, 0.5, v, 8, w)
            TravelMath.etherwarpLanding(0.5, 2.62, 0.5, v, 61, w, pitch)
            TravelMath.blinkLanding(0.5, 1.0, 0.5, v, 0, w)
        }
        assertTrue(TravelMath.aim(0.0, 0.0, 0.0, 0.0, 0.0, 0.0).all { it.isFinite() })
        for (s in listOf(0.0, 1.5, 10.0)) repeat(200) { assertTrue(TravelMath.aimError(s, r).all { it.isFinite() }) }
        val m = TravelMath.CostModel()
        for (mv in Move.entries) for (dist in listOf(0.0, 61.0, 1e6)) assertTrue(TravelMath.cost(mv, dist, 300, 1, m).isFinite())
    }
}
