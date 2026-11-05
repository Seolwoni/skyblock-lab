package com.github.noamm9.utils

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Grid path finding without Minecraft types (unit tested). The world is asked through the callbacks, so the
 * same search runs on a real level (Auto Fishing) or on a test grid.
 */
object PathMath {
    data class P(val x: Int, val y: Int, val z: Int) {
        fun offset(dx: Int, dy: Int, dz: Int) = P(x + dx, y + dy, z + dz)
        fun distManhattan(o: P) = abs(x - o.x) + abs(y - o.y) + abs(z - o.z)
        fun distSqr(o: P): Double { val dx = (x - o.x).toDouble(); val dy = (y - o.y).toDouble(); val dz = (z - o.z).toDouble(); return dx * dx + dy * dy + dz * dz }
    }

    /** What the search needs to know about the world. */
    interface World {
        /** Feet can stand here (solid below, room for feet and head, no fluid). */
        fun standable(p: P): Boolean
        /** The block two above [p] is free (room to step up). */
        fun headroom(p: P): Boolean
        /** Lava within [r] blocks around (and just below) [p]. */
        fun nearLava(p: P, r: Int): Boolean
    }

    private val DIRS = listOf(1 to 0, - 1 to 0, 0 to 1, 0 to - 1, 1 to 1, 1 to - 1, - 1 to 1, - 1 to - 1)
    private val HEIGHTS = intArrayOf(0, 1, - 1, - 2, - 3)

    /**
     * The blocks one walking step from [from], in search order, with their step cost (1 straight, 1.414 diagonal,
     * +0.5 for a height change, +6 next to lava). 8 directions, no corner cutting, up 1 / down 3, one height per direction.
     */
    fun walkNeighbours(from: P, world: World): List<Pair<P, Double>> {
        val out = ArrayList<Pair<P, Double>>(8)
        for ((dx, dz) in DIRS) {
            for (dy in HEIGHTS) {
                val n = from.offset(dx, dy, dz)
                if (! world.standable(n)) continue
                if (dx != 0 && dz != 0 && (! world.standable(from.offset(dx, 0, 0)) || ! world.standable(from.offset(0, 0, dz)))) continue
                if (dy == 1 && ! world.headroom(from)) continue
                if (world.nearLava(n, 0)) continue
                out += n to (if (dx != 0 && dz != 0) 1.414 else 1.0) + (if (dy != 0) 0.5 else 0.0) + (if (world.nearLava(n, 1)) 6.0 else 0.0)
                break // one height per direction (the first that fits)
            }
        }
        return out
    }

    /**
     * A* over standable blocks: never steps into or next to lava, keeps a block's distance from lava when it can,
     * 8 directions (no corner cutting), up 1 / down 3. Null when there's no path within [maxExpand] nodes or
     * the goal is more than [maxManhattan] away.
     */
    fun aStar(start: P, goal: P, world: World, maxExpand: Int = 6000, maxManhattan: Int = 160): List<P>? {
        if (start.distManhattan(goal) > maxManhattan) return null
        data class N(val pos: P, val g: Double, val f: Double)
        val open = PriorityQueue<N>(compareBy { it.f })
        val came = HashMap<P, P>()
        val best = HashMap<P, Double>()
        fun h(b: P) = sqrt(b.distSqr(goal))
        open += N(start, 0.0, h(start))
        best[start] = 0.0
        var expanded = 0
        while (open.isNotEmpty() && expanded++ < maxExpand) {
            val cur = open.poll()
            if (cur.pos.distManhattan(goal) <= 1 && abs(cur.pos.y - goal.y) <= 1) {
                val out = ArrayList<P>()
                var c: P? = cur.pos
                while (c != null) { out += c; c = came[c] }
                out.reverse()
                return out
            }
            if (cur.g > (best[cur.pos] ?: Double.MAX_VALUE)) continue
            for ((n, step) in walkNeighbours(cur.pos, world)) {
                val g = cur.g + step
                if (g < (best[n] ?: Double.MAX_VALUE)) {
                    best[n] = g
                    came[n] = cur.pos
                    open += N(n, g, g + h(n))
                }
            }
        }
        return null
    }
}
