package com.github.noamm9.utils

import com.github.noamm9.features.impl.general.HumanBehaviour
import com.github.noamm9.features.impl.routes.AssistBridge
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.phys.Vec3
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One place for every automatic feature to look and wait like a person (the Assist mods' way):
 * turns that speed up, cruise and ease in along a slight arc with a fresh top speed each time,
 * reaction delays from [HumanTiming], aim points that are never the exact centre, and a short
 * reaction before acting on something that just appeared. Settings live in [HumanBehaviour].
 * With Assist Core installed and "Use Assist Core" on, turning is handed to Assist Core itself.
 */
object Humanize {
    private val rnd = Random()
    val timing = HumanTiming()

    // turning state (Assist Core Body.turnTo, ported)
    private var vel = 0f
    private var turnSpeed = 10f
    private var curve = 0f
    private var curveFrom = 0f
    private var lastYaw = 0f
    private var lastPitch = 0f

    private val pace get() = HumanBehaviour.pace.value.coerceIn(0, 2)
    private val on get() = HumanBehaviour.enabled

    /** Call once per tick (HumanBehaviour does it). */
    fun tick() {
        timing.slipChance = HumanBehaviour.slips.value / 100.0
        timing.drift = HumanBehaviour.drift.value / 100.0
        timing.fatiguePerHour = HumanBehaviour.fatigue.value / 100.0
        timing.tick()
    }

    private fun wrap(d: Float): Float {
        var x = d % 360f
        if (x > 180f) x -= 360f
        if (x < - 180f) x += 360f
        return x
    }

    /** A reaction delay in ticks within [min, max], scaled by pace. Plain uniform when Human Behaviour is off. */
    fun delay(min: Int, max: Int): Int {
        if (! on) return if (max <= min) min else min + rnd.nextInt(max - min + 1)
        val scale = doubleArrayOf(1.0, 0.75, 0.5)[pace]
        val t = timing.next(min, max)
        return (min + (t - min) * scale).toInt().coerceAtLeast(min.coerceAtMost(max))
    }

    /** [base] ticks with some human spread around it (about ±40%, never below 1). */
    fun around(base: Int): Int = if (base <= 0) 0 else delay((base * 0.7).toInt().coerceAtLeast(1), (base * 1.5).toInt().coerceAtLeast(1))

    /** Ticks to wait before acting on something that just showed up. */
    fun reaction(): Int = if (! on) 0 else delay(HumanBehaviour.reactMin.value, HumanBehaviour.reactMax.value)

    /** The odd longer pause between actions (0 most of the time). */
    fun maybeBreak(): Int = if (! on) 0 else timing.maybeBreak(HumanBehaviour.breaks.value, 30, 100)

    private val aimOffsets = HashMap<Long, Vec3>()

    /** [point] moved by a small offset that stays the same for the same spot (so the aim doesn't shake). */
    fun aimPoint(point: Vec3, maxOffset: Double = HumanBehaviour.aimSpread.value): Vec3 {
        if (! on || maxOffset <= 0) return point
        val key = (Math.floor(point.x * 2).toLong() * 73856093L) xor (Math.floor(point.y * 2).toLong() * 19349663L) xor (Math.floor(point.z * 2).toLong() * 83492791L)
        if (aimOffsets.size > 512) aimOffsets.clear()
        val off = aimOffsets.getOrPut(key) {
            Vec3((rnd.nextDouble() * 2 - 1) * maxOffset, (rnd.nextDouble() * 2 - 1) * maxOffset * 0.7, (rnd.nextDouble() * 2 - 1) * maxOffset)
        }
        return point.add(off)
    }

    /** Fresh top speed for the next turn, so no two movements look the same. */
    fun newTurn() {
        val scale = floatArrayOf(1f, 1.25f, 1.6f)[pace]
        turnSpeed = (7f + rnd.nextFloat() * 7f) * scale * HumanBehaviour.turnSpeed.value.toFloat()
    }

    /**
     * Turns towards yaw / pitch like a hand on a mouse: speeds up, cruises, eases in, along a gentle arc.
     * Returns true once within [tolerance] degrees. With Human Behaviour off it snaps.
     */
    fun turnTo(p: LocalPlayer, yaw: Float, pitch: Float, tolerance: Float = 1.5f): Boolean {
        if (! on) {
            PlayerUtils.rotate(yaw, pitch)
            return true
        }
        if (HumanBehaviour.useAssistCore.value && AssistBridge.coreLoaded) return AssistBridge.turn(p, yaw, pitch, tolerance)
        val dy = wrap(yaw - p.yRot)
        val dp = pitch - p.xRot
        val dist = sqrt(dy * dy + dp * dp)
        if (dist <= tolerance) {
            vel *= 0.5f
            return true
        }
        if (abs(wrap(yaw - lastYaw)) + abs(pitch - lastPitch) > 15f || dist > curveFrom + 15f) {
            newTurn()
            val lively = doubleArrayOf(1.0, 0.7, 0.4)[pace]
            curve = ((0.15 + rnd.nextDouble() * 0.3) * (if (rnd.nextBoolean()) 1 else - 1) * lively * (if (HumanBehaviour.curvedTurns.value) 1 else 0)).toFloat()
            curveFrom = dist
        }
        lastYaw = yaw
        lastPitch = pitch
        val accel = maxOf(0.6f, turnSpeed * 0.3f)
        var want = minOf(turnSpeed, sqrt(2f * accel * dist))
        want = maxOf(want, 0.6f)
        vel = if (vel < want) minOf(want, vel + accel) else maxOf(want, vel - accel * 1.5f)
        val step = minOf(vel, dist)
        val ux = dy / dist
        val uy = dp / dist
        val bend = if (dist > 4f) curve * minOf(1f, dist / 25f) else 0f
        val mx = ux - uy * bend
        val my = uy + ux * bend
        val ml = sqrt(mx * mx + my * my)
        PlayerUtils.rotate(p.yRot + step * mx / ml, (p.xRot + step * my / ml).coerceIn(- 90f, 90f))
        return dist - step <= tolerance
    }

    fun lookAt(p: LocalPlayer, target: Vec3, tolerance: Float = 1.5f): Boolean {
        val rot = MathUtils.calcYawPitch(aimPoint(target))
        return turnTo(p, rot.yaw, rot.pitch, tolerance)
    }
}
