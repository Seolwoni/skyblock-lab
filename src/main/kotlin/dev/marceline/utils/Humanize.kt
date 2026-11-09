package dev.marceline.utils

import dev.marceline.features.impl.general.HumanBehaviour
import dev.marceline.features.impl.routes.AssistBridge
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

    private val pace get() = HumanBehaviour.pace.value.coerceIn(0, 2)
    private val on get() = HumanBehaviour.enabled

    /** The turning state and maths (Assist Core Body.turnTo, ported); the settings are read on every call. */
    internal val engine = TurnEngine(rnd) { turnConfig() }

    private fun turnConfig() = TurnConfig(
        pace = pace,
        turnSpeed = HumanBehaviour.turnSpeed.value,
        overshoot = HumanBehaviour.overshoot.value,
        overshootMin = HumanBehaviour.overshootMin.value.toFloat(),
        overshootMax = HumanBehaviour.overshootMax.value.toFloat(),
        curvedTurns = HumanBehaviour.curvedTurns.value,
        stepSpread = HumanBehaviour.stepSpread.value,
        pauseRatio = HumanBehaviour.pauseTicks.value / 100.0,
        snap = on && HumanBehaviour.mouseGrid.value,
        sensitivity = net.minecraft.client.Minecraft.getInstance().options.sensitivity().get(),
    )

    private fun gameTick(): Long = net.minecraft.client.Minecraft.getInstance().level?.gameTime ?: 0L

    /** Turn to [yaw]/[pitch] over the next 50 ms, a little every frame (not in one jump on the tick). */
    fun glide(p: LocalPlayer, yaw: Float, pitch: Float) = engine.glide(PlayerView(p), yaw, pitch, gameTick())

    /** Finishes a glide that's still under way (called at the start of every tick, so logic sees where we ended up). */
    fun settle(p: LocalPlayer) = engine.settle(PlayerView(p))

    /** Call every frame. */
    fun frame() {
        if (! engine.gliding) return
        val mc = net.minecraft.client.Minecraft.getInstance()
        val p = mc.player ?: return run { engine.settle(object: ViewAccess { override var yaw = 0f; override var pitch = 0f }) }
        // follow the game's own tick clock (partial tick 0 -> 1 until the next tick), so the glide ends exactly when
        // the next tick's step starts: no stop-and-go at tick edges whatever the frame rate or tick timing
        engine.frame(PlayerView(p), gameTick(), mc.deltaTracker.getGameTimeDeltaPartialTick(true))
    }

    // ---- the only way automatic code changes where the player looks

    /** Set the view (mouse-sensitivity steps applied). Every automatic turn ends up here. */
    fun setView(p: LocalPlayer, yaw: Float, pitch: Float) = write(p, yaw, pitch)

    /** The single writer: the change from the current view, in whole mouse counts (Mouse Grid), then applied. */
    private fun write(p: LocalPlayer, yaw: Float, pitch: Float) = engine.write(PlayerView(p), yaw, pitch)

    /** Move the view by a small amount (per-frame followers: tracking, steering). */
    fun nudgeView(p: LocalPlayer, dYaw: Float, dPitch: Float) = setView(p, p.yRot + dYaw, p.xRot + dPitch)

    /** The human-behaviour profile from the Human Behaviour settings. */
    fun profile(): HumanProfile = runCatching {
        HumanProfile(
            failRate = HumanBehaviour.mistakeRate.value / 100.0,
            delaySpread = HumanBehaviour.delaySpread.value / 100.0,
            slipChance = HumanBehaviour.slips.value / 100.0,
        )
    }.getOrDefault(HumanProfile())

    /** Call once per tick (HumanBehaviour does it). */
    fun tick() {
        net.minecraft.client.Minecraft.getInstance().player?.let { settle(it) }
        timing.slipChance = HumanBehaviour.slips.value / 100.0
        timing.drift = HumanBehaviour.drift.value / 100.0
        timing.fatiguePerHour = HumanBehaviour.fatigue.value / 100.0
        timing.apply(profile())
        timing.tick()
    }

    private fun wrap(d: Float): Float = HumanMotion.wrapDeg(d)

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
            HumanMotion.faceOffset(maxOffset, rnd).let { Vec3(it[0], it[1] * 0.7, it[2]) }
        }
        return point.add(off)
    }

    /** Forgets the turn in progress (speed, arc, overshoot, last target). Only [AimBroker] calls this, when the view changes hands. */
    internal fun resetTurnState() = engine.resetTurnState()

    /** Fresh top speed for the next turn, so no two movements look the same. */
    fun newTurn() = engine.newTurn()

    /**
     * Turns towards yaw / pitch like a hand on a mouse: speeds up, cruises, eases in, along a gentle arc.
     * Returns true once within [tolerance] degrees. With Human Behaviour off it snaps.
     */
    fun turnTo(p: LocalPlayer, yaw: Float, pitch: Float, tolerance: Float = 1.5f, overshoot: Boolean = true): Boolean {
        if (! on) {
            PlayerUtils.rotate(yaw, pitch)
            return true
        }
        if (HumanBehaviour.useAssistCore.value && AssistBridge.coreLoaded) {
            // Assist Core works out the step; it's drawn with our frame glide like every other turn
            engine.settleOlder(PlayerView(p), gameTick())
            val y0 = p.yRot; val p0 = p.xRot
            val done = AssistBridge.turn(p, yaw, pitch, tolerance)
            val y1 = p.yRot; val p1 = p.xRot
            p.yRot = y0; p.xRot = p0
            glide(p, y1, p1)
            return done && kotlin.math.abs(wrap(yaw - y0)) <= tolerance * 4
        }
        return engine.turn(PlayerView(p), yaw, pitch, tolerance, overshoot, gameTick())
    }

    fun lookAt(p: LocalPlayer, target: Vec3, tolerance: Float = 1.5f): Boolean {
        val rot = MathUtils.calcYawPitch(aimPoint(target))
        return turnTo(p, rot.yaw, rot.pitch, tolerance)
    }
}
