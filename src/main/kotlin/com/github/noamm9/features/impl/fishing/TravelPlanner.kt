package com.github.noamm9.features.impl.fishing

import com.github.noamm9.features.impl.fishing.TravelMath.Move
import com.github.noamm9.utils.PathMath
import com.github.noamm9.utils.PathMath.P
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Route search with teleports (pure, no Minecraft types): walking edges are PathMath's, plus AOTE / AOTV blinks
 * (8 blocks along a look) and etherwarps (raycast onto a block). Costs come from [TravelMath.cost]. Runs on a worker
 * thread; over its time budget it gives up and the caller walks.
 */
object TravelPlanner {
    data class Options(
        val blink: Boolean,
        val ether: Boolean,
        val maxChain: Int = 3,
        /** Current mana, or null when unknown (then teleports are allowed and the server says no if it's short). */
        val mana: Int? = null,
        val manaReserve: Int = 0,
        val blinkMana: Int = 50,
        val etherMana: Int = 45,
        /** Soulflow, or null when unknown: no etherwarp edges at all then. */
        val soulflow: Int? = null,
        val etherSoulflow: Int = 1,
        val cost: TravelMath.CostModel = TravelMath.CostModel(),
        /** Edges turned off for this session after failing ([edgeKey]). */
        val disabled: Set<String> = emptySet(),
        /** No teleport from within this many blocks of the goal, and none landing closer: the end is walked. */
        val walkLast: Int = 3,
        val eyeHeight: Double = 1.62,
        val sneakEyeHeight: Double = 1.27,
        val pitchMin: Double = - 90.0,
        val pitchMax: Double = 90.0,
        val blinkRange: Int = 8,
        val etherRange: Int = 61,
        val budgetNanos: Long = 8_000_000L,
        val clock: () -> Long = System::nanoTime,
        val maxExpand: Int = 6000,
        val maxManhattan: Int = 160,
    )

    data class Leg(val move: Move, val from: P, val to: P, val yaw: Double = 0.0, val pitch: Double = 0.0)

    /** [legs] empty with [overBudget] = the search ran out of time (walk instead). */
    data class Plan(val legs: List<Leg>, val overBudget: Boolean = false) {
        val teleports get() = legs.count { it.move != Move.WALK }
    }

    fun edgeKey(move: Move, from: P, to: P) = "$move ${from.x},${from.y},${from.z}>${to.x},${to.y},${to.z}"

    private fun flat(a: P, b: P): Double { val dx = (a.x - b.x).toDouble(); val dz = (a.z - b.z).toDouble(); return sqrt(dx * dx + dz * dz) }
    private fun dist(a: P, b: P) = sqrt(a.distSqr(b))

    private data class Key(val p: P, val chain: Int)
    private class N(val key: Key, val g: Double, val f: Double, val mana: Int?)

    /** Best route from [start] to [goal], or null when there is none. */
    fun plan(start: P, goal: P, world: PathMath.World, blocks: TravelMath.Blocks, o: Options): Plan? {
        if (start.distManhattan(goal) > o.maxManhattan) return null
        val t0 = o.clock()
        val m = o.cost
        val canBlink = o.blink
        val canEther = o.ether && o.soulflow != null && o.soulflow >= o.etherSoulflow
        // cheapest cost per block of any allowed move, so the heuristic never overestimates
        var perBlock = 1.0 / m.walkSpeed
        if (canBlink) perBlock = minOf(perBlock, TravelMath.cost(Move.BLINK, 0.0, o.blinkMana, 0, m) / o.blinkRange)
        if (canEther) perBlock = minOf(perBlock, TravelMath.cost(Move.ETHERWARP, 0.0, o.etherMana, o.etherSoulflow, m) / o.etherRange)
        fun h(p: P) = dist(p, goal) * perBlock

        val open = PriorityQueue<N>(compareBy { it.f })
        val best = HashMap<Key, Double>()
        val came = HashMap<Key, Pair<Key, Leg>>()
        val s = Key(start, 0)
        open += N(s, 0.0, h(start), o.mana)
        best[s] = 0.0
        var expanded = 0
        fun relax(cur: N, to: P, leg: Leg, chain: Int, c: Double, mana: Int?) {
            val k = Key(to, chain)
            val g = cur.g + c
            if (g < (best[k] ?: Double.MAX_VALUE)) {
                best[k] = g
                came[k] = cur.key to leg
                open += N(k, g, g + h(to), mana)
            }
        }
        while (open.isNotEmpty() && expanded++ < o.maxExpand) {
            if ((expanded and 15) == 0 && o.clock() - t0 > o.budgetNanos) return Plan(emptyList(), overBudget = true)
            val cur = open.poll()
            val p = cur.key.p
            if (p.distManhattan(goal) <= 1 && abs(p.y - goal.y) <= 1) {
                val legs = ArrayList<Leg>()
                var k = cur.key
                while (k != s) { val (prev, leg) = came[k]!!; legs += leg; k = prev }
                legs.reverse()
                return Plan(legs)
            }
            if (cur.g > (best[cur.key] ?: Double.MAX_VALUE)) continue

            for ((n, step) in PathMath.walkNeighbours(p, world)) relax(cur, n, Leg(Move.WALK, p, n), 0, step / m.walkSpeed, cur.mana)

            val chain = cur.key.chain
            if (chain >= o.maxChain || flat(p, goal) <= o.walkLast) continue
            val here = dist(p, goal)
            fun usable(to: P, move: Move): Boolean =
                to != p && flat(p, to) >= 2.5 && dist(to, goal) < here - 1 && flat(to, goal) >= o.walkLast.coerceAtMost(here.toInt() - 1) &&
                    edgeKey(move, p, to) !in o.disabled && world.standable(to)

            if (canBlink && (cur.mana == null || TravelMath.affordable(cur.mana, o.blinkMana, o.manaReserve))) {
                val towards = TravelMath.aim(p.x + 0.5, p.y + o.eyeHeight, p.z + 0.5, goal.x + 0.5, p.y + o.eyeHeight, goal.z + 0.5)[0]
                val yaws = listOf(towards, towards - 22.5, towards + 22.5, towards - 45.0, towards + 45.0)
                for (yaw in yaws) for (pitch in doubleArrayOf(0.0, - 20.0, 20.0)) {
                    val land = TravelMath.blinkLanding(p.x + 0.5, p.y.toDouble(), p.z + 0.5, TravelMath.look(yaw, pitch), o.blinkRange, blocks) ?: continue
                    if (! usable(land, Move.BLINK)) continue
                    relax(cur, land, Leg(Move.BLINK, p, land, yaw, pitch), chain + 1,
                        TravelMath.cost(Move.BLINK, dist(p, land), o.blinkMana, 0, m), cur.mana?.minus(o.blinkMana))
                }
            }
            if (canEther && (cur.mana == null || TravelMath.affordable(cur.mana, o.etherMana, o.manaReserve))) {
                val ex = p.x + 0.5; val ey = p.y + o.sneakEyeHeight; val ez = p.z + 0.5
                val total = flat(p, goal)
                for (d in doubleArrayOf(61.0, 50.0, 40.0, 30.0, 20.0, 12.0)) {
                    val reach = minOf(d, total - o.walkLast)
                    if (reach < 6) continue
                    val f = reach / total
                    val cx = (p.x + (goal.x - p.x) * f).toInt(); val cz = (p.z + (goal.z - p.z) * f).toInt()
                    val cy = p.y + ((goal.y - p.y) * f).toInt()
                    // the top of a column near that point
                    val c = (6 downTo - 6).map { P(cx, cy + it, cz) }.firstOrNull { TravelMath.landable(it, blocks) } ?: continue
                    val a = TravelMath.aim(ex, ey, ez, c.x + 0.5, c.y - 0.05, c.z + 0.5)
                    val land = TravelMath.etherwarpLanding(ex, ey, ez, TravelMath.look(a[0], a[1]), o.etherRange, blocks, a[1], o.pitchMin, o.pitchMax) ?: continue
                    if (! usable(land, Move.ETHERWARP)) continue
                    relax(cur, land, Leg(Move.ETHERWARP, p, land, a[0], a[1]), chain + 1,
                        TravelMath.cost(Move.ETHERWARP, dist(p, land), o.etherMana, o.etherSoulflow, m), cur.mana?.minus(o.etherMana))
                }
            }
        }
        return null
    }
}
