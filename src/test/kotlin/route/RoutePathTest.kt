package route

import dev.riftwalk.utils.HumanMotion
import dev.riftwalk.utils.PathMath
import dev.riftwalk.utils.PathMath.P
import java.util.Random
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Re-planning a route: the same world gives the same path every time; only the per-waypoint offsets vary. */
class RoutePathTest {
    /** A flat 40x40 floor (feet at y=1) with seeded walls and lava. */
    private class Grid(seed: Long, val size: Int = 40) : PathMath.World {
        val wall = HashSet<Pair<Int, Int>>()
        val lava = HashSet<Pair<Int, Int>>()
        init {
            val r = Random(seed)
            repeat(size * size / 6) { wall += r.nextInt(size) to r.nextInt(size) }
            repeat(size * size / 40) { lava += r.nextInt(size) to r.nextInt(size) }
            for (c in listOf(0 to 0, size - 1 to size - 1, 1 to 0, 0 to 1)) { wall -= c; lava -= c }
        }
        fun inside(x: Int, z: Int) = x in 0 until size && z in 0 until size
        override fun standable(p: P) = p.y == 1 && inside(p.x, p.z) && (p.x to p.z) !in wall && (p.x to p.z) !in lava
        override fun headroom(p: P) = true
        override fun nearLava(p: P, r: Int): Boolean {
            for (dx in - r .. r) for (dz in - r .. r) if ((p.x + dx to p.z + dz) in lava) return true
            return false
        }
    }

    private val start = P(0, 1, 0)

    @Test fun sameWorldSamePath() {
        var found = 0
        for (seed in 1L .. 60L) {
            val goal = P(39, 1, 39)
            val a = PathMath.aStar(start, goal, Grid(seed))
            val b = PathMath.aStar(start, goal, Grid(seed))
            val c = PathMath.aStar(start, goal, Grid(seed))
            assertEquals(a, b, "seed $seed: two plans differ")
            assertEquals(a, c)
            if (a != null) found++
        }
        assertTrue(found >= 20, "too few grids had a path ($found) - the test isn't checking much")
    }

    @Test fun pathsAreValid() {
        for (seed in 1L .. 60L) {
            val g = Grid(seed)
            val goal = P(39, 1, 39)
            val path = PathMath.aStar(start, goal, g) ?: continue
            assertEquals(start, path.first())
            assertTrue(path.last().distManhattan(goal) <= 1)
            for (q in path) {
                assertTrue(g.standable(q), "seed $seed: $q not standable")
                assertTrue(! g.nearLava(q, 0), "seed $seed: $q is lava")
            }
            for ((u, v) in path.zipWithNext()) {
                assertTrue(abs(u.x - v.x) <= 1 && abs(u.z - v.z) <= 1 && u != v, "seed $seed: jump $u -> $v")
                if (u.x != v.x && u.z != v.z) // no corner cutting
                    assertTrue(g.standable(P(v.x, 1, u.z)) && g.standable(P(u.x, 1, v.z)), "seed $seed: cut a corner at $u -> $v")
            }
        }
    }

    @Test fun replanSamePathOnlyOffsetsDiffer() {
        val g = Grid(7)
        val goal = P(39, 1, 39)
        val path = assertNotNull(PathMath.aStar(start, goal, g) ?: (1L .. 60L).firstNotNullOfOrNull { PathMath.aStar(start, goal, Grid(it)) })
        fun offsets(seed: Long): List<List<Double>> {
            val r = Random(seed)
            var prev: DoubleArray? = null
            return path.map { HumanMotion.waypointOffset(0.15, prev, r).also { prev = it }.toList() }
        }
        val again = PathMath.aStar(start, goal, g) ?: path
        assertEquals(path, again, "re-planning changed the path")
        val o1 = offsets(1); val o2 = offsets(2)
        assertEquals(o1, offsets(1), "same seed, different offsets")
        val differ = o1.zip(o2).count { (a, b) -> a != b }
        assertTrue(differ == path.size, "only $differ of ${path.size} waypoints got a different offset")
        assertTrue((o1 + o2).flatten().all { abs(it) <= 0.15 })
    }

    @Test fun limitsGiveNull() {
        assertEquals(null, PathMath.aStar(start, P(200, 1, 0), Grid(1)), "past the distance limit")
        val boxed = object : PathMath.World {
            override fun standable(p: P) = p == start
            override fun headroom(p: P) = true
            override fun nearLava(p: P, r: Int) = false
        }
        assertEquals(null, PathMath.aStar(start, P(10, 1, 10), boxed), "walled in")
    }
}
