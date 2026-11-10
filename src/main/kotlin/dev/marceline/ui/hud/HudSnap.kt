package dev.marceline.ui.hud

import kotlin.math.abs

/**
 * Alignment while dragging in the HUD editor: the dragged box's left edge, centre or right edge (and top,
 * middle, bottom) catch on the same lines of the other boxes and on the screen centre when they come close.
 */
object HudSnap {
    data class Box(val left: Float, val top: Float, val w: Float, val h: Float)

    /** How far to move the dragged box, and where to draw the guide lines (null = none on that axis). */
    data class Result(val dx: Float, val dy: Float, val vLine: Float?, val hLine: Float?)

    fun snap(m: Box, others: List<Box>, screenW: Float, screenH: Float, within: Float = 4f): Result {
        val (dx, v) = axis(listOf(m.left, m.left + m.w / 2f, m.left + m.w), others.flatMap { listOf(it.left, it.left + it.w / 2f, it.left + it.w) } + screenW / 2f, within)
        val (dy, h) = axis(listOf(m.top, m.top + m.h / 2f, m.top + m.h), others.flatMap { listOf(it.top, it.top + it.h / 2f, it.top + it.h) } + screenH / 2f, within)
        return Result(dx, dy, v, h)
    }

    private fun axis(mine: List<Float>, lines: List<Float>, within: Float): Pair<Float, Float?> {
        var best: Float? = null
        var line: Float? = null
        for (a in mine) for (t in lines) {
            val d = t - a
            if (abs(d) <= within && (best == null || abs(d) < abs(best))) { best = d; line = t }
        }
        return (best ?: 0f) to line
    }
}
