package dev.marceline.features.impl.fishing

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The pure maths of fishing, with no game objects, so it can be unit tested:
 * the vanilla hook throw and the Hotspot Radar line crossing.
 */
object FishingMath {
    /** Hook drag per tick and gravity per tick (vanilla FishingHook). */
    const val DRAG = 0.92
    const val GRAVITY = 0.03

    /** Start of a throw: hook offset from the eye (x, z) and its first velocity (x, y, z). */
    data class Throw(val offX: Double, val offZ: Double, val vx: Double, val vy: Double, val vz: Double)

    /** Vanilla cast: 0.3 in front of the eye, speed 0.6 / length + 0.5 along the look. */
    fun throwStart(yaw: Float, pitch: Float): Throw {
        val r = Math.PI / 180.0
        val f2 = cos(- yaw * r - Math.PI)
        val f3 = sin(- yaw * r - Math.PI)
        val f4 = - cos(- pitch * r)
        val f5 = sin(- pitch * r)
        var vx = - f3
        var vy = (- (f5 / f4)).coerceIn(- 5.0, 5.0)
        var vz = - f2
        val len = sqrt(vx * vx + vy * vy + vz * vz)
        val k = 0.6 / len + 0.5
        vx *= k; vy *= k; vz *= k
        return Throw(- f3 * 0.3, - f2 * 0.3, vx, vy, vz)
    }

    /**
     * Where a throw from the eye at (x, y, z) comes down through the plane y = [surface], ignoring blocks.
     * Null when it never gets there within 160 ticks.
     */
    fun landingOnPlane(x: Double, y: Double, z: Double, yaw: Float, pitch: Float, surface: Double): DoubleArray? {
        val t = throwStart(yaw, pitch)
        var px = x + t.offX
        var py = y
        var pz = z + t.offZ
        var vx = t.vx; var vy = t.vy; var vz = t.vz
        repeat(160) {
            val nx = px + vx; val ny = py + vy; val nz = pz + vz
            if (ny <= surface && vy < 0) return doubleArrayOf(nx, ny, nz)
            px = nx; py = ny; pz = nz
            vx *= DRAG; vy = vy * DRAG - GRAVITY; vz *= DRAG
        }
        return null
    }

    /**
     * Where two flat lines cross: from (ax, az) heading (dax, daz) and from (bx, bz) heading (dbx, dbz).
     * Null when they're within [minAngleDeg] of parallel or the crossing is behind the first one.
     */
    fun cross(ax: Double, az: Double, dax: Double, daz: Double, bx: Double, bz: Double, dbx: Double, dbz: Double, minAngleDeg: Double = 15.0): DoubleArray? {
        val c = dax * dbz - daz * dbx
        if (kotlin.math.abs(c) < sin(Math.toRadians(minAngleDeg))) return null
        val t = ((bx - ax) * dbz - (bz - az) * dbx) / c
        if (t < 0) return null
        return doubleArrayOf(ax + dax * t, az + daz * t)
    }
}
