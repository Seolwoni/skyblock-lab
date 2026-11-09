package dev.marceline.utils

import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The settings a turn reads, taken from Human Behaviour once per call (so the engine itself has no game objects).
 * [snap]: view changes in whole mouse counts for [sensitivity] (Mouse Grid, only with Human Behaviour on).
 */
data class TurnConfig(
    val pace: Int = 0,
    val turnSpeed: Double = 1.0,
    val overshoot: Boolean = true,
    val overshootMin: Float = 0.1f,
    val overshootMax: Float = 0.3f,
    val curvedTurns: Boolean = true,
    val stepSpread: Double = 0.4,
    val pauseRatio: Double = 0.08,
    val snap: Boolean = true,
    val sensitivity: Double = 0.5,
)

/**
 * The turning state and maths behind [Humanize] (Assist Core Body.turnTo, ported): turns that speed up, cruise and
 * ease in along a slight arc with a fresh top speed each time, overshoot and come back, each tick's step spread over
 * the frames until the next tick (frame glide). Works on a [ViewAccess], so it runs against a fake in tests.
 */
class TurnEngine(private val rnd: Random, private val config: () -> TurnConfig) {
    // turning state
    var vel = 0f; internal set
    var turnSpeed = 10f; internal set
    var curve = 0f; internal set
    var curveFrom = 0f; internal set
    var lastYaw = 0f; internal set
    var lastPitch = 0f; internal set
    // overshoot: a big turn aims a little past the target, then comes back (Overshoot Min / Max)
    var osYaw = 0f; internal set
    var osPitch = 0f; internal set

    // frame glide: a tick's turn is spread over the frames until the next tick
    var gliding = false; private set
    private var gFromYaw = 0f
    private var gFromPitch = 0f
    private var gToYaw = 0f
    private var gToPitch = 0f
    private var gTick = 0L
    /** How far into tick [gTick] the glide started (0, or where a glide replaced mid-tick had got to). */
    private var gT0 = 0f
    /** The partial tick of the last frame drawn. */
    private var gLastT = 0f

    private fun wrap(d: Float): Float = HumanMotion.wrapDeg(d)

    /**
     * Turn to [yaw]/[pitch] over the rest of game tick [now], a little every frame.
     * A glide left from an earlier tick is finished first (as before). A glide of this same tick is not: the new one
     * starts from where the view is now and takes over the frames that are left, so nothing jumps.
     */
    fun glide(v: ViewAccess, yaw: Float, pitch: Float, now: Long) {
        settleOlder(v, now)
        gT0 = if (gliding) gLastT.coerceIn(0f, 1f) else 0f
        gFromYaw = v.yaw
        gFromPitch = v.pitch
        gToYaw = HumanMotion.glideTarget(gFromYaw, yaw)
        gToPitch = pitch.coerceIn(- 90f, 90f)
        gTick = now
        gLastT = gT0
        gliding = true
    }

    /** Finishes a glide from a tick before [now]; one of this tick stays under way. */
    fun settleOlder(v: ViewAccess, now: Long) {
        if (gliding && gTick != now) settle(v)
    }

    /** Finishes a glide that's still under way. */
    fun settle(v: ViewAccess) {
        if (! gliding) return
        gliding = false
        write(v, gToYaw, gToPitch)
    }

    /** One frame of the glide: [now] is the game tick, [partial] how far into it the frame is (0..1). */
    fun frame(v: ViewAccess, now: Long, partial: Float) {
        if (! gliding) return
        val t = if (gTick == now) partial.coerceIn(0f, 1f) else 1f
        // share of the glide done by now: t itself for a glide that started with the tick (gT0 = 0)
        val s = if (gT0 >= 1f) 1f else ((t - gT0) / (1f - gT0)).coerceIn(0f, 1f)
        write(v, HumanMotion.glideAt(gFromYaw, gToYaw, s), HumanMotion.glideAt(gFromPitch, gToPitch, s))
        gLastT = t
        if (t >= 1f) gliding = false
    }

    /** The single writer: the change from the current view, in whole mouse counts when [TurnConfig.snap], then applied. */
    fun write(v: ViewAccess, yaw: Float, pitch: Float) {
        val c = config()
        var dy = wrap(yaw - v.yaw).toDouble()
        var dp = (pitch.coerceIn(- 90f, 90f) - v.pitch).toDouble()
        if (c.snap) {
            dy = HumanMotion.gcdSnap(dy, c.sensitivity)
            dp = HumanMotion.gcdSnap(dp, c.sensitivity)
        }
        v.set((v.yaw + dy).toFloat(), (v.pitch + dp).toFloat().coerceIn(- 90f, 90f))
    }

    /** Fresh top speed for the next turn, so no two movements look the same. */
    fun newTurn() {
        val c = config()
        val scale = floatArrayOf(1f, 1.25f, 1.6f)[c.pace.coerceIn(0, 2)]
        turnSpeed = (7f + rnd.nextFloat() * 7f) * scale * c.turnSpeed.toFloat()
    }

    /** Forgets the turn in progress: speed, arc, overshoot and the last target (a new owner starts clean). */
    fun resetTurnState() {
        vel = 0f; turnSpeed = 10f; curve = 0f; curveFrom = 0f
        lastYaw = 0f; lastPitch = 0f; osYaw = 0f; osPitch = 0f
    }

    /**
     * One tick of a turn towards [yaw] / [pitch]: speeds up, cruises, eases in, along a gentle arc, with an overshoot
     * on big turns. The step is glided over the frames of tick [now]. True once within [tolerance] degrees.
     */
    fun turn(v: ViewAccess, yaw: Float, pitch: Float, tolerance: Float, overshoot: Boolean, now: Long): Boolean {
        val c = config()
        val pace = c.pace.coerceIn(0, 2)
        settleOlder(v, now)
        val ry = wrap(yaw - v.yaw)
        val rp = pitch - v.pitch
        val realDist = sqrt(ry * ry + rp * rp)
        val fresh = abs(wrap(yaw - lastYaw)) + abs(pitch - lastPitch) > 15f || realDist > curveFrom + 15f
        if (fresh) {
            // a new turn: maybe aim a bit past it (share of the turn from Human Behaviour, never on small turns)
            val extra = if (overshoot && c.overshoot) HumanMotion.overshoot(realDist, c.overshootMin, c.overshootMax, rnd) else 0f
            osYaw = if (realDist > 0f) ry / realDist * extra else 0f
            osPitch = if (realDist > 0f) rp / realDist * extra else 0f
            if (pitch + osPitch !in - 90f .. 90f) osPitch = 0f
        }
        val dy = wrap(yaw + osYaw - v.yaw)
        val dp = pitch + osPitch - v.pitch
        val dist = sqrt(dy * dy + dp * dp)
        if (dist <= tolerance) {
            if (osYaw != 0f || osPitch != 0f) { // reached the overshoot point: now correct back
                osYaw = 0f; osPitch = 0f
                curveFrom = realDist
                lastYaw = yaw; lastPitch = pitch
                return false
            }
            vel *= 0.5f
            return true
        }
        if (fresh) {
            newTurn()
            val lively = doubleArrayOf(1.0, 0.7, 0.4)[pace]
            curve = ((0.15 + rnd.nextDouble() * 0.3) * (if (rnd.nextBoolean()) 1 else - 1) * lively * (if (c.curvedTurns) 1 else 0)).toFloat()
            curveFrom = realDist
        }
        lastYaw = yaw
        lastPitch = pitch
        val accel = maxOf(0.6f, turnSpeed * 0.3f)
        var want = minOf(turnSpeed, sqrt(2f * accel * dist))
        want = maxOf(want, 0.6f)
        vel = if (vel < want) minOf(want, vel + accel) else maxOf(want, vel - accel * 1.5f)
        // this tick's share: log-normal around the eased speed, or nothing at all (Step Spread / Pause Ticks)
        val step = HumanMotion.turnStep(minOf(vel, dist), c.stepSpread, c.pauseRatio, rnd).coerceAtMost(dist)
        if (step <= 0f) return false
        val ux = dy / dist
        val uy = dp / dist
        val bend = if (dist > 4f) curve * minOf(1f, dist / 25f) else 0f
        val mx = ux - uy * bend
        val my = uy + ux * bend
        val ml = sqrt(mx * mx + my * my)
        // spread this tick's step over the coming frames; we count as "there" from the next tick on
        glide(v, v.yaw + step * mx / ml, (v.pitch + step * my / ml).coerceIn(- 90f, 90f), now)
        return false
    }
}
