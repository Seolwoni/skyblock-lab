package com.github.noamm9.utils

import java.util.Random
import kotlin.math.abs
import kotlin.math.min

/**
 * Pure maths for small human imperfections (no Minecraft types, so it can be unit tested).
 * Every number comes in as a parameter; the callers take them from settings.
 */
object HumanMotion {
    /**
     * One step of a bounded random walk: moves [current] by a normal step of size [step] and reflects off
     * ±[bound], so the aim wanders around the anchor without ever drifting away from it.
     */
    fun walk(current: Float, step: Float, bound: Float, rnd: Random): Float {
        if (bound <= 0f) return 0f
        var v = current + (rnd.nextGaussian() * step).toFloat()
        // reflect (twice covers any step up to 2x the range)
        repeat(2) {
            if (v > bound) v = 2 * bound - v
            if (v < - bound) v = - 2 * bound - v
        }
        return v.coerceIn(- bound, bound)
    }

    /**
     * How far past the target a turn of [dist] degrees goes: a share in [minShare]..[maxShare] of the turn,
     * with the turn counted at most [cap] degrees (a 90° flick doesn't overshoot by 27°).
     * Turns of [minTurn] degrees or less don't overshoot.
     */
    fun overshoot(dist: Float, minShare: Float, maxShare: Float, rnd: Random, cap: Float = 10f, minTurn: Float = 20f): Float {
        if (dist <= minTurn || maxShare <= 0f) return 0f
        val lo = min(minShare, maxShare)
        val share = lo + rnd.nextFloat() * (maxShare - lo)
        return share * min(dist, cap)
    }

    /** A point offset inside a face: each axis uniform in ±[maxOffset] (never past half a block). */
    fun faceOffset(maxOffset: Double, rnd: Random): DoubleArray {
        val m = abs(maxOffset).coerceAtMost(0.49)
        return DoubleArray(3) { (rnd.nextDouble() * 2 - 1) * m }
    }

    /** Uniform pick in [min]..[max] (inclusive), order-insensitive. */
    fun between(min: Int, max: Int, rnd: Random): Int {
        val lo = minOf(min, max); val hi = maxOf(min, max)
        return lo + rnd.nextInt(hi - lo + 1)
    }

    /** True with probability [percent] / 100. */
    fun chance(percent: Double, rnd: Random) = percent > 0 && rnd.nextDouble() * 100 < percent
}
