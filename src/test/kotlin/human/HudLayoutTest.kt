package human

import com.github.noamm9.utils.HudLayout
import com.github.noamm9.utils.HudLayout.R
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HudLayoutTest {
    @Test fun apartPanelsDontMove() {
        val d = HudLayout.offsets(listOf(R(0f, 0f, 50f, 20f), R(100f, 0f, 50f, 20f), R(0f, 100f, 50f, 20f)), 500f)
        assertEquals(listOf(0f, 0f, 0f), d)
    }

    @Test fun overlappingPanelGoesJustBelow() {
        val d = HudLayout.offsets(listOf(R(10f, 10f, 100f, 40f), R(20f, 30f, 80f, 20f)), 500f, gap = 3f)
        assertEquals(0f, d[0])
        assertEquals(10f + 40f + 3f - 30f, d[1])
    }

    @Test fun atTheBottomItGoesAbove() {
        val d = HudLayout.offsets(listOf(R(0f, 460f, 100f, 30f), R(0f, 470f, 100f, 25f)), 500f, gap = 3f)
        assertEquals(460f - 25f - 3f - 470f, d[1])
    }

    @Test fun neverOverlapAfterwardsAndEmptyIgnored() {
        val r = Random(5)
        repeat(300) {
            val rects = List(8) { R(r.nextFloat() * 300, r.nextFloat() * 300, 20 + r.nextFloat() * 80, 10 + r.nextFloat() * 40) } + R(5f, 5f, 0f, 0f)
            val d = HudLayout.offsets(rects, 2000f, gap = 2f)
            val moved = rects.mapIndexed { i, q -> q.copy(y = q.y + d[i]) }.filter { it.w > 0 && it.h > 0 }
            for (i in moved.indices) for (j in i + 1 until moved.size) assertTrue(! moved[i].overlaps(moved[j], 1.9f), "still overlapping: ${moved[i]} ${moved[j]}")
            assertEquals(0f, d.last())
        }
        assertEquals(emptyList(), HudLayout.offsets(emptyList(), 100f))
    }
}
