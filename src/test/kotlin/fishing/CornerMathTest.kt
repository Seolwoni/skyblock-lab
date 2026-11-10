package fishing

import dev.marceline.features.impl.fishing.CornerMath
import dev.marceline.features.impl.fishing.CornerMath.Dir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CornerMathTest {
    private fun spot(walls: Set<Dir>, lava: Map<Dir, Int?> = emptyMap(), dist: Double = 2.0, x: Int = 0) =
        CornerMath.Spot(x, 64, 0, walls, Dir.entries.filter { it !in walls }.associateWith { lava[it] }, dist)

    @Test
    fun twoWallsAtRightAnglesAreACorner() {
        assertTrue(CornerMath.isCorner(setOf(Dir.N, Dir.E)))
        assertTrue(CornerMath.isCorner(setOf(Dir.S, Dir.W, Dir.E)))
        assertTrue(! CornerMath.isCorner(setOf(Dir.N, Dir.S)))
        assertTrue(! CornerMath.isCorner(setOf(Dir.W)))
    }

    @Test
    fun lavaCloseAlongAnOpenSideRulesItOut() {
        assertNull(CornerMath.score(spot(setOf(Dir.N, Dir.E), mapOf(Dir.S to 2))))
        assertTrue(CornerMath.score(spot(setOf(Dir.N, Dir.E), mapOf(Dir.S to 5))) != null)
        // lava behind a wall doesn't matter: the wall is what we lean on
        assertTrue(CornerMath.score(spot(setOf(Dir.N, Dir.E), mapOf(Dir.S to null, Dir.W to null))) != null)
    }

    @Test
    fun moreWallsAndLessWalkingWin() {
        val pocket = spot(setOf(Dir.N, Dir.E, Dir.W), dist = 4.0, x = 1)
        val corner = spot(setOf(Dir.N, Dir.E), dist = 4.0, x = 2)
        val near = spot(setOf(Dir.N, Dir.E), dist = 1.0, x = 3)
        assertEquals(listOf(1, 3, 2), CornerMath.rank(listOf(corner, near, pocket)).map { it.x })
    }

    @Test
    fun aWallOrOpenGroundIsNotRanked() {
        assertEquals(emptyList(), CornerMath.rank(listOf(spot(setOf(Dir.N)), spot(emptySet()))))
    }
}
