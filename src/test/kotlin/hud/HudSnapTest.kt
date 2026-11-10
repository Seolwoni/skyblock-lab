package hud

import dev.marceline.ui.hud.HudSnap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HudSnapTest {
    private val other = HudSnap.Box(100f, 100f, 50f, 20f)

    @Test
    fun leftEdgeCatchesOnAnotherLeftEdge() {
        val r = HudSnap.snap(HudSnap.Box(102.5f, 300f, 30f, 10f), listOf(other), 1000f, 1000f)
        assertEquals(-2.5f, r.dx)
        assertEquals(100f, r.vLine)
    }

    @Test
    fun nothingCloseMeansNoMove() {
        val r = HudSnap.snap(HudSnap.Box(300f, 300f, 30f, 11f), listOf(other), 1000f, 1000f)
        assertEquals(0f, r.dx); assertEquals(0f, r.dy)
        assertNull(r.vLine); assertNull(r.hLine)
    }

    @Test
    fun centreCatchesOnScreenCentre() {
        val r = HudSnap.snap(HudSnap.Box(483f, 700f, 30f, 10f), emptyList(), 1000f, 1000f)
        assertEquals(2f, r.dx)
        assertEquals(500f, r.vLine)
    }

    @Test
    fun theClosestLineWins() {
        // bottom (123) is 3 from 120 (other's bottom), top (113) is 3 from 110 (other's middle) - middle 118 is 2 from 120
        val r = HudSnap.snap(HudSnap.Box(400f, 113f, 30f, 10f), listOf(other), 1000f, 1000f)
        assertEquals(2f, r.dy)
        assertEquals(120f, r.hLine)
    }
}
