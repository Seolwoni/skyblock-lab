package dev.riftwalk.utils

/**
 * Keeps HUD panels from sitting on top of each other (pure, unit tested). Works on screen rectangles in draw order:
 * a panel that overlaps one placed before it moves down just below it (or above it when that would leave the screen).
 * Only the drawing moves; the saved positions stay where you put them.
 */
object HudLayout {
    data class R(val x: Float, val y: Float, val w: Float, val h: Float) {
        fun overlaps(o: R, gap: Float) = x < o.x + o.w + gap && o.x < x + w + gap && y < o.y + o.h + gap && o.y < y + h + gap
    }

    /** Vertical offset for each rectangle (same order). [gap] keeps panels this far apart. */
    fun offsets(rects: List<R>, screenH: Float, gap: Float = 3f): List<Float> {
        val placed = ArrayList<R>()
        val out = ArrayList<Float>(rects.size)
        for (r in rects) {
            if (r.w <= 0f || r.h <= 0f) { out += 0f; continue }
            var cur = r
            var tries = 0
            while (tries++ < 12) {
                val hit = placed.firstOrNull { cur.overlaps(it, gap) } ?: break
                val below = cur.copy(y = hit.y + hit.h + gap)
                cur = if (below.y + below.h <= screenH) below else cur.copy(y = hit.y - cur.h - gap).takeIf { it.y >= 0f } ?: break
            }
            placed += cur
            out += cur.y - r.y
        }
        return out
    }
}
