package route

import com.github.noamm9.features.impl.fishing.TravelMath
import com.github.noamm9.features.impl.fishing.TravelMath.Candidate
import com.github.noamm9.features.impl.fishing.TravelMath.Why
import com.github.noamm9.utils.PathMath.P
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The overlay's own logic: candidate order, closest working spot, why a spot doesn't work. The landing check is TravelMath's. */
class EtherwarpSpotsTest {
    private class World(val top: Int = 255) : TravelMath.Blocks {
        val solids = HashSet<P>()
        val lavas = HashSet<P>()
        override fun solid(p: P) = p in solids
        override fun passable(p: P) = p !in solids && p !in lavas
        override fun lava(p: P) = p in lavas
        override fun inWorld(y: Int) = y in 0 .. top
    }

    private fun c(x: Int, why: Why, d: Double) = Candidate(P(x, 1, 0), why, d)

    @Test fun sortValidFirstThenByDistanceStable() {
        val list = listOf(c(1, Why.SPACE, 3.0), c(2, Why.OK, 9.0), c(3, Why.OK, 4.0), c(4, Why.RANGE, 70.0), c(5, Why.OK, 4.0))
        val s = TravelMath.sortCandidates(list)
        assertEquals(listOf(3, 5, 2, 1, 4), s.map { it.feet.x })
        // same input in any order gives the same output (no flicker)
        val r = Random(3)
        repeat(50) { assertEquals(s, TravelMath.sortCandidates(list.shuffled(r))) }
    }

    @Test fun nearestIsTheClosestWorkingSpot() {
        val list = listOf(c(1, Why.LAVA, 2.0), c(2, Why.OK, 12.0), c(3, Why.OK, 7.5), c(4, Why.SPACE, 1.0))
        assertEquals(3, TravelMath.nearestValid(list)?.feet?.x)
        // ties: the same one every time
        val tie = listOf(c(9, Why.OK, 5.0), c(8, Why.OK, 5.0))
        assertEquals(8, TravelMath.nearestValid(tie)?.feet?.x)
        assertEquals(8, TravelMath.nearestValid(tie.reversed())?.feet?.x)
    }

    @Test fun noCandidatesAndAllInvalidDontCrash() {
        assertEquals(emptyList(), TravelMath.sortCandidates(emptyList()))
        assertNull(TravelMath.nearestValid(emptyList()))
        val bad = listOf(c(1, Why.SPACE, 3.0), c(2, Why.LAVA, 5.0), c(3, Why.RANGE, 66.0), c(4, Why.WORLD, 10.0))
        assertNull(TravelMath.nearestValid(bad))
        assertEquals(4, TravelMath.sortCandidates(bad).size)
        // nothing to hit at all: empty, no crash
        assertEquals(emptyList(), TravelMath.etherwarpCandidates(0.5, 2.6, 0.5, 0.0, -80.0, 61, World()))
    }

    @Test fun reasonsAreClassified() {
        val eye = doubleArrayOf(0.5, 2.27, 0.5)
        fun spotsLookingAt(w: World, x: Double, y: Double, z: Double, range: Int = 61): List<Candidate> {
            val a = TravelMath.aim(eye[0], eye[1], eye[2], x, y, z)
            return TravelMath.etherwarpCandidates(eye[0], eye[1], eye[2], a[0], a[1], range, w, cone = 0.0)
        }
        // a lone block 20 away: works
        val ok = World().apply { solids += P(20, 0, 0) }
        assertEquals(Why.OK, spotsLookingAt(ok, 20.5, 0.95, 0.5).single().why)
        // something right on top of it: no room
        val space = World().apply { solids += P(20, 0, 0); solids += P(20, 2, 0) }
        assertEquals(Why.SPACE, spotsLookingAt(space, 20.5, 0.95, 0.5).single().why)
        // lava beside it
        val lava = World().apply { solids += P(20, 0, 0); lavas += P(21, 1, 0) }
        assertEquals(Why.LAVA, spotsLookingAt(lava, 20.5, 0.95, 0.5).single().why)
        // 70 away with a 61 range: out of range (still shown)
        val far = World().apply { solids += P(70, 0, 0) }
        assertEquals(Why.RANGE, spotsLookingAt(far, 70.5, 0.95, 0.5).single().why)
        // a block at the top of the world: the head would be above it
        val roof = World(top = 3).apply { solids += P(20, 2, 0) }
        assertEquals(Why.WORLD, spotsLookingAt(roof, 20.5, 2.95, 0.5).single().why)
    }

    @Test fun manyRaysGiveOneCandidatePerSpotAndAllFinite() {
        val w = World()
        for (x in 0 .. 70) for (z in -10 .. 10) w.solids += P(x, 0, z)
        w.lavas += P(15, 1, 2)
        val list = TravelMath.etherwarpCandidates(0.5, 2.27, 0.5, -90.0, 10.0, 61, w)
        assertTrue(list.isNotEmpty())
        assertEquals(list.size, list.map { it.feet }.toSet().size, "a spot listed twice")
        assertTrue(list.all { it.dist.isFinite() && it.dist >= 0 })
        assertEquals(list, TravelMath.sortCandidates(list), "not sorted")
        assertTrue(list.any { it.why == Why.OK })
    }

    @Test fun fadeIsClamped() {
        assertEquals(0f, TravelMath.fade(0, 300))
        assertEquals(0.5f, TravelMath.fade(150, 300))
        assertEquals(1f, TravelMath.fade(10_000, 300))
        assertEquals(0f, TravelMath.fade(-5, 300))
        assertEquals(1f, TravelMath.fade(0, 0))
    }
}
