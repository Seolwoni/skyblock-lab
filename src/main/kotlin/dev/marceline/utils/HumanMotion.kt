package dev.marceline.utils

import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

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

    // ---- input quantization: a mouse moves the view in whole counts of a step set by the sensitivity

    /**
     * Degrees of view per mouse count at [sensitivity] (0..1, the game's slider), the game's own formula:
     * f = s * 0.6 + 0.2, step = f³ * 8 * 0.15.
     */
    fun gcd(sensitivity: Double): Double {
        val f = sensitivity.coerceIn(0.0, 1.0) * 0.6 + 0.2
        return f * f * f * 8.0 * 0.15
    }

    /** [delta] degrees rounded to a whole number of mouse counts at [sensitivity]. */
    fun gcdSnap(delta: Double, sensitivity: Double): Double {
        val g = gcd(sensitivity)
        return round(delta / g) * g
    }

    // ---- turn trace: per-tick steps spread log-normally, with ticks where the hand doesn't move

    /**
     * One tick of a turn: [base] scaled by a log-normal factor (median 1, sigma [spread]),
     * or 0 with probability [pauseRatio] (a tick with no mouse movement).
     */
    fun turnStep(base: Float, spread: Double, pauseRatio: Double, rnd: Random): Float {
        if (pauseRatio > 0 && rnd.nextDouble() < pauseRatio) return 0f
        if (spread <= 0) return base
        return (base * exp(rnd.nextGaussian() * spread)).toFloat()
    }

    // ---- cast-to-cast variance: each cast's offset leans on the last one (AR(1))

    /** Next value of a series with standard deviation [sigma] and lag-1 correlation [carry] (0..1). */
    fun carried(prev: Double, sigma: Double, carry: Double, rnd: Random): Double {
        val c = carry.coerceIn(0.0, 0.99)
        return prev * c + rnd.nextGaussian() * sigma * sqrt(1 - c * c)
    }

    // ---- clicks

    /**
     * Ticks until the next click: log-normal around [medianTicks] (sigma [spread]), at least [minTicks],
     * never the same as [last] (two equal gaps in a row get moved by one).
     */
    fun clickInterval(medianTicks: Double, spread: Double, rnd: Random, last: Int = - 1, minTicks: Int = 1): Int {
        val v = if (spread <= 0) medianTicks else medianTicks * exp(rnd.nextGaussian() * spread)
        var t = maxOf(minTicks, Math.round(v).toInt())
        if (t == last) t = if (t <= minTicks || rnd.nextBoolean()) t + 1 else t - 1
        return t
    }

    // ---- movement

    /** Log-normal tick count around [median] (sigma [spread]), clamped to [min]..[max]. */
    fun lognormalTicks(median: Double, spread: Double, min: Int, max: Int, rnd: Random): Int {
        val lo = minOf(min, max); val hi = maxOf(min, max)
        val v = if (spread <= 0) median else median * exp(rnd.nextGaussian() * spread)
        return Math.round(v).toInt().coerceIn(lo, hi)
    }

    /** [base] ticks moved by up to ±[spreadShare] of itself (uniform), at least 1. */
    fun jittered(base: Int, spreadShare: Double, rnd: Random): Int {
        if (base <= 0) return 1
        val d = (rnd.nextDouble() * 2 - 1) * spreadShare.coerceIn(0.0, 1.0) * base
        return maxOf(1, Math.round(base + d).toInt())
    }

    /**
     * A fresh (x, z) offset for a waypoint's arrival point, each axis uniform in ±[maxPerAxis];
     * never exactly [previous] (drawn again if it is).
     */
    fun waypointOffset(maxPerAxis: Double, previous: DoubleArray?, rnd: Random): DoubleArray {
        val m = abs(maxPerAxis)
        if (m == 0.0) return doubleArrayOf(0.0, 0.0)
        while (true) {
            val o = doubleArrayOf((rnd.nextDouble() * 2 - 1) * m, (rnd.nextDouble() * 2 - 1) * m)
            if (previous == null || ! o.contentEquals(previous)) return o
        }
    }

    /** Largest per-axis offset for an arrival [radius] that keeps the waypoint centre itself an arrival (|o| <= radius / 2). */
    fun offsetCap(radius: Double) = radius / 2 / sqrt(2.0)

    /**
     * Arrival with an offset centre: within (radius - |offset|) of the offset point. That circle lies inside the
     * original one, so every position that counts as arrived also counted before (same arrival coordinates).
     */
    fun arrived(dx: Double, dz: Double, ox: Double, oz: Double, radius: Double): Boolean {
        val r = radius - sqrt(ox * ox + oz * oz)
        if (r <= 0) return dx == 0.0 && dz == 0.0
        val ex = dx - ox; val ez = dz - oz
        return ex * ex + ez * ez < r * r
    }

    // ---- view glide (Humanize.glide / frame)

    /** [d] degrees folded into -180..180 (same arithmetic Humanize always used). */
    fun wrapDeg(d: Float): Float {
        var x = d % 360f
        if (x > 180f) x -= 360f
        if (x < - 180f) x += 360f
        return x
    }

    /** Where a glide from [from] to [to] is at partial tick [t] (0..1). */
    fun glideAt(from: Float, to: Float, t: Float): Float = from + (to - from) * t

    /** The glide's end yaw for a turn to [yaw] from [fromYaw]: the short way round. */
    fun glideTarget(fromYaw: Float, yaw: Float): Float = fromYaw + wrapDeg(yaw - fromYaw)
}
