package com.github.noamm9.features.impl.fishing

import com.github.noamm9.utils.HumanMotion
import com.github.noamm9.utils.PathMath.P
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Teleport travel maths without Minecraft types (unit tested): where a blink / etherwarp lands, what an edge costs,
 * where a route needs a re-aim, and the human parts (aim error, gaps between blinks).
 */
object TravelMath {
    enum class Move { WALK, BLINK, ETHERWARP }

    /** What the maths needs to know about blocks. */
    interface Blocks {
        /** Something you can stand on (has a collision shape, not fluid). */
        fun solid(p: P): Boolean
        /** Feet / head can be here (no collision shape, not fluid). */
        fun passable(p: P): Boolean
        fun lava(p: P): Boolean
        /** Inside the world (build height). */
        fun inWorld(y: Int): Boolean
    }

    /** A landing needs [air] free blocks (feet up), something solid under the feet, no lava around and inside the world. */
    fun landable(feet: P, b: Blocks, air: Int = 2): Boolean {
        if (! b.inWorld(feet.y - 1) || ! b.inWorld(feet.y + air - 1)) return false
        for (i in 0 until air) if (! b.passable(feet.offset(0, i, 0))) return false
        if (! b.solid(feet.offset(0, - 1, 0))) return false
        return ! lavaNear(feet, b)
    }

    /** Lava in or next to the landing (feet level and the block under it). */
    fun lavaNear(feet: P, b: Blocks): Boolean {
        for (dx in - 1 .. 1) for (dz in - 1 .. 1) for (dy in - 1 .. 0) if (b.lava(feet.offset(dx, dy, dz))) return true
        return false
    }

    /** Unit look vector for Minecraft yaw / pitch (degrees). */
    fun look(yaw: Double, pitch: Double): DoubleArray {
        val y = Math.toRadians(yaw); val p = Math.toRadians(pitch)
        return doubleArrayOf(- sin(y) * cos(p), - sin(p), cos(y) * cos(p))
    }

    /** Yaw / pitch (degrees) to look from [ex, ey, ez] at [tx, ty, tz]. */
    fun aim(ex: Double, ey: Double, ez: Double, tx: Double, ty: Double, tz: Double): DoubleArray {
        val dx = tx - ex; val dy = ty - ey; val dz = tz - ez
        val flat = sqrt(dx * dx + dz * dz)
        return doubleArrayOf(Math.toDegrees(atan2(- dx, dz)), Math.toDegrees(- atan2(dy, flat)))
    }

    private fun cell(x: Double, y: Double, z: Double) = P(floor(x).toInt(), floor(y).toInt(), floor(z).toInt())

    /**
     * An AOTE / AOTV / Wither Impact blink from feet [fx, fy, fz] along [dir]: the furthest landable spot up to
     * [range] blocks before anything blocks the way (checked every 0.25 blocks), or null when it can't move.
     */
    fun blinkLanding(fx: Double, fy: Double, fz: Double, dir: DoubleArray, range: Int, b: Blocks, air: Int = 2): P? {
        var best: P? = null
        val steps = range * 4
        for (i in 1 .. steps) {
            val t = i * 0.25
            val c = cell(fx + dir[0] * t, fy + dir[1] * t, fz + dir[2] * t)
            if (! b.inWorld(c.y)) break
            var clear = true
            for (k in 0 until air) if (! b.passable(c.offset(0, k, 0))) { clear = false; break }
            if (! clear) break
            if (i % 4 == 0 || i == steps) if (landable(c, b, air)) best = c
        }
        return best
    }

    /**
     * An etherwarp from eye [ex, ey, ez] along [dir]: the first solid block within [range] (voxel walk), and the spot
     * on top of it if that's landable and the look is within [pitchMin]..[pitchMax]. Null otherwise.
     */
    fun etherwarpLanding(ex: Double, ey: Double, ez: Double, dir: DoubleArray, range: Int, b: Blocks, pitch: Double, pitchMin: Double = - 90.0, pitchMax: Double = 90.0, air: Int = 2): P? {
        if (pitch < pitchMin || pitch > pitchMax) return null
        var x = floor(ex).toInt(); var y = floor(ey).toInt(); var z = floor(ez).toInt()
        val sx = if (dir[0] > 0) 1 else - 1; val sy = if (dir[1] > 0) 1 else - 1; val sz = if (dir[2] > 0) 1 else - 1
        fun first(o: Double, d: Double, s: Int): Double = if (d == 0.0) Double.MAX_VALUE else ((if (s > 0) floor(o) + 1 - o else o - floor(o)) / abs(d))
        var tx = first(ex, dir[0], sx); var ty = first(ey, dir[1], sy); var tz = first(ez, dir[2], sz)
        val dtx = if (dir[0] == 0.0) Double.MAX_VALUE else 1 / abs(dir[0])
        val dty = if (dir[1] == 0.0) Double.MAX_VALUE else 1 / abs(dir[1])
        val dtz = if (dir[2] == 0.0) Double.MAX_VALUE else 1 / abs(dir[2])
        while (true) {
            val t = minOf(tx, ty, tz)
            if (t > range) return null
            when (t) {
                tx -> { x += sx; tx += dtx }
                ty -> { y += sy; ty += dty }
                else -> { z += sz; tz += dtz }
            }
            if (! b.inWorld(y)) return null
            val c = P(x, y, z)
            if (b.solid(c)) return c.offset(0, 1, 0).takeIf { landable(it, b, air) }
            if (! b.passable(c)) return null // fluid: no landing through it
        }
    }

    /** What moving costs, as ticks. */
    data class CostModel(
        /** Blocks per tick walking (sprinting is about 0.28). */
        val walkSpeed: Double = 0.28,
        /** Ticks for one blink / etherwarp: aim, pause, the teleport, settling. */
        val teleportTicks: Double = 8.0,
        /** Mana regained per tick (Hypixel: 2% of max mana per second = max * 0.001 per tick). */
        val manaPerTick: Double = 1.0,
        /** Cost per tick of mana regen the move uses (setting, 1 = one tick of regen costs one tick). */
        val manaWeight: Double = 1.0,
        /** Cost per soulflow used (setting). */
        val soulflowWeight: Double = 40.0,
    ) {
        init {
            require(walkSpeed > 0 && manaPerTick > 0) { "walkSpeed / manaPerTick must be > 0" }
            require(teleportTicks >= 0 && manaWeight >= 0 && soulflowWeight >= 0) { "costs must be >= 0" }
        }
    }

    /** Cost of an edge: move ticks + mana as regen ticks x weight + soulflow x weight. */
    fun cost(move: Move, dist: Double, mana: Int, soulflow: Int, m: CostModel): Double = when (move) {
        Move.WALK -> dist / m.walkSpeed
        else -> m.teleportTicks + mana / m.manaPerTick * m.manaWeight + soulflow * m.soulflowWeight
    }

    /** True when a move costing [cost] mana leaves at least [reserve] (kept for fighting). */
    fun affordable(mana: Int, cost: Int, reserve: Int) = mana - cost >= reserve

    /** A step of a smoothed route: a move, or a re-aim pause between two teleports (the view is off after one). */
    data class Step(val move: Move?, val to: P)

    /** Puts a re-aim step (move = null, at the landing) between every two teleports in a row. */
    fun smooth(steps: List<Pair<Move, P>>): List<Step> {
        val out = ArrayList<Step>()
        var prev: Move? = null
        for ((m, p) in steps) {
            if (m != Move.WALK && prev != null && prev != Move.WALK) out += Step(null, out.last().to)
            out += Step(m, p)
            prev = m
        }
        return out
    }

    /**
     * Aim error for one teleport (degrees, yaw and pitch): its size is log-normal around [median] (sigma [spread]),
     * in a random direction (pitch at 60%). Median 1.5 with spread 0.42 gives P99 about 4 degrees.
     */
    fun aimError(median: Double, spread: Double, rnd: Random): DoubleArray {
        if (median <= 0) return doubleArrayOf(0.0, 0.0)
        val size = median * kotlin.math.exp(rnd.nextGaussian() * spread.coerceAtLeast(0.0))
        val a = rnd.nextDouble() * 2 * Math.PI
        return doubleArrayOf(size * cos(a), size * sin(a) * 0.6)
    }

    /**
     * Ticks between two blinks in a row: log-normal around [median], [min]..[max], and never the same gap three
     * times running ([last] and [beforeLast] are the previous two).
     */
    fun blinkGap(median: Double, spread: Double, min: Int, max: Int, last: Int, beforeLast: Int, rnd: Random): Int {
        var t = HumanMotion.lognormalTicks(median, spread, min, max, rnd)
        if (min >= max) return t
        if (t == last && t == beforeLast) t = if (t < max && (t == min || rnd.nextBoolean())) t + 1 else t - 1
        return t
    }
}
