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
    /** Each axis its own step, ceiling and start (false: the old single step along one direction). */
    val axisSplit: Boolean = true,
    /** Pitch top speed as a share of yaw's ([HumanProfile.pitchShare]). */
    val pitchShare: Double = 0.75,
    /** Pitch start lag in ticks on big new turns ([HumanProfile.pitchLag]). */
    val pitchLag: Int = 2,
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
    // axis independence: the pitch bend, pitch start lag, pauses per axis, when the last turn ended
    var curvePitch = 0f; internal set
    var lagLeft = 0; internal set
    private var pausedYaw = false
    private var pausedPitch = false
    private var lastEnd = - 1000L

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
        curvePitch = 0f; lagLeft = 0; pausedYaw = false; pausedPitch = false; lastEnd = - 1000L
    }

    /**
     * One tick of a turn towards [yaw] / [pitch]: speeds up, cruises, eases in, along a gentle arc, with an overshoot
     * on big turns. The step is glided over the frames of tick [now]. True once within [tolerance] degrees.
     */
    fun turn(v: ViewAccess, yaw: Float, pitch: Float, tolerance: Float, overshoot: Boolean, now: Long, lagAllowed: Boolean = true): Boolean {
        val c = config()
        if (c.axisSplit) return turnByAxis(v, yaw, pitch, tolerance, overshoot, now, lagAllowed, c)
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

    /**
     * A tick of a turn with each axis on its own budget: the hand speed (eased like before) is split by direction;
     * pitch is capped at [TurnConfig.pitchShare] of the top speed, starts [TurnConfig.pitchLag] ticks late on big new
     * turns, lands no earlier than yaw and gets its own noise, pauses and bend. Pitch tolerance is 1.4x yaw's.
     * Nothing of this at the ±90 edge.
     */
    private fun turnByAxis(v: ViewAccess, yaw: Float, pitch: Float, tolerance: Float, overshoot: Boolean, now: Long, lagAllowed: Boolean, c: TurnConfig): Boolean {
        val pace = c.pace.coerceIn(0, 2)
        settleOlder(v, now)
        val ry = wrap(yaw - v.yaw)
        val rp = pitch - v.pitch
        val realDist = sqrt(ry * ry + rp * rp)
        val fresh = abs(wrap(yaw - lastYaw)) + abs(pitch - lastPitch) > 15f || realDist > curveFrom + 15f
        if (fresh) {
            val extra = if (overshoot && c.overshoot) HumanMotion.overshoot(realDist, c.overshootMin, c.overshootMax, rnd) else 0f
            osYaw = if (realDist > 0f) ry / realDist * extra else 0f
            osPitch = if (realDist > 0f) rp / realDist * extra else 0f
            if (pitch + osPitch !in - 90f .. 90f) osPitch = 0f
        }
        var dy = wrap(yaw + osYaw - v.yaw)
        var dp = pitch + osPitch - v.pitch
        val tolPitch = tolerance * PITCH_TOLERANCE
        if (abs(dy) <= tolerance && abs(dp) <= tolPitch && (osYaw != 0f || osPitch != 0f)) {
            // reached the overshoot point: correct back, starting this same tick (no dead tick in between)
            osYaw = 0f; osPitch = 0f
            curveFrom = realDist
            dy = ry; dp = rp
        }
        if (abs(dy) <= tolerance && abs(dp) <= tolPitch) {
            vel *= 0.5f
            lagLeft = 0
            lastEnd = now
            lastYaw = yaw; lastPitch = pitch
            return true
        }
        val edge = HumanMotion.nearPitchEdge(pitch)
        lagLeft = HumanMotion.lagRemaining(lagLeft, fresh, c.pitchLag, realDist, rp, edge, lagAllowed, tracking = now - lastEnd <= TRACKING_TICKS)
        if (fresh) {
            newTurn()
            val lively = doubleArrayOf(1.0, 0.7, 0.4)[pace]
            val bends = if (c.curvedTurns) 1 else 0
            curve = ((0.15 + rnd.nextDouble() * 0.3) * (if (rnd.nextBoolean()) 1 else - 1) * lively * bends).toFloat()
            curvePitch = ((0.05 + rnd.nextDouble() * 0.15) * (if (rnd.nextBoolean()) 1 else - 1) * lively * bends).toFloat()
            curveFrom = realDist
        }
        lastYaw = yaw
        lastPitch = pitch
        val dist = sqrt(dy * dy + dp * dp)
        val accel = maxOf(0.6f, turnSpeed * 0.3f)
        var want = minOf(turnSpeed, sqrt(2f * accel * dist))
        want = maxOf(want, 0.6f)
        vel = if (vel < want) minOf(want, vel + accel) else maxOf(want, vel - accel * 1.5f)

        // the hand's speed split by direction; pitch under its own ceiling
        val ay = abs(dy); val ap = abs(dp)
        val yawBudget = if (ay > tolerance) maxOf(vel * ay / dist, 0.3f) else 0f
        val pitchBudget = if (ap > tolPitch) minOf(maxOf(vel * ap / dist, 0.3f), HumanMotion.pitchCeiling(turnSpeed, c.pitchShare, dp, edge)) else 0f
        val sy = HumanMotion.axisStep(yawBudget, c.stepSpread, c.pauseRatio, rnd, pausedYaw).coerceAtMost(ay)
        pausedYaw = yawBudget > 0f && sy == 0f
        var sp: Float
        if (lagLeft > 0) {
            sp = 0f
            lagLeft--
            pausedPitch = false
        } else {
            sp = HumanMotion.axisStep(pitchBudget, c.stepSpread, c.pauseRatio, rnd, pausedPitch).coerceAtMost(ap)
            pausedPitch = pitchBudget > 0f && sp == 0f
        }
        val sgnY = if (dy < 0f) - 1f else 1f
        val sgnP = if (dp < 0f) - 1f else 1f
        // pitch lands no earlier than yaw: while yaw is still out (on its way, or over the target on an overshoot),
        // pitch stops a hair outside its tolerance around the real target
        val hold = tolPitch + 0.1f
        val yawOut = ay > tolerance || abs(wrap(yaw - v.yaw)) > tolerance
        val rpNow = pitch - v.pitch
        if (! edge && yawOut && abs(rpNow) > hold && sgnP == (if (rpNow < 0f) - 1f else 1f)) sp = minOf(sp, abs(rpNow) - hold)
        if (sy <= 0f && sp <= 0f) return false
        // each axis bends with the other's movement, by its own amount and side
        val bend = if (dist > 4f) minOf(1f, dist / 25f) else 0f
        // (an axis that has arrived isn't pushed off again, and nothing bends pitch at the ±90 edge)
        val ddy = sgnY * sy - (if (ay > tolerance) curve * bend * sgnP * sp else 0f)
        val ddp = sgnP * sp + (if (ap > tolPitch && ! edge) curvePitch * bend * sgnY * sy else 0f)
        glide(v, v.yaw + ddy, (v.pitch + ddp).coerceIn(- 90f, 90f), now)
        return false
    }

    companion object {
        /** Pitch tolerance as a multiple of yaw's. */
        const val PITCH_TOLERANCE = 1.4f
        /** A turn that starts within this many ticks of the last one ending is following something: no new lag. */
        const val TRACKING_TICKS = 6L
    }
}
