package dev.marceline.utils

import dev.marceline.event.EventBus
import dev.marceline.event.impl.TickEvent
import dev.marceline.init.types.ISelfInit

/** Who gets the view when two automatic features want it at once. A higher one takes it from a lower one. */
enum class AimPriority { IDLE, TRAVEL, COMBAT, CRITICAL }

/**
 * The view is one thing: one owner at a time moves it. A feature asks for it ([acquire]) with its priority and then
 * turns through the broker, passing itself as the owner. Calls from anyone but the holder do nothing.
 *
 * - [acquire]: free or already ours -> true. Someone else's: taken only with a strictly higher priority.
 * - Moves ([turnTo], [glide], [setView], [nudgeView]) renew a lease of [LeasedAimBroker.leaseTicks]; a holder that
 *   stops moving loses the view when it runs out ([tick]).
 * - The turn state (speed, arc, overshoot, last target) is reset only when the view goes to a different owner, so one
 *   feature on its own turns exactly as it did without the broker.
 */
interface AimBroker {
    val holder: Any?

    fun acquire(owner: Any, p: AimPriority): Boolean
    fun turnTo(owner: Any, yaw: Float, pitch: Float, tolerance: Float, overshoot: Boolean = true): Boolean
    fun glide(owner: Any, yaw: Float, pitch: Float): Boolean
    fun setView(owner: Any, yaw: Float, pitch: Float): Boolean
    fun nudgeView(owner: Any, dYaw: Float, dPitch: Float): Boolean
    fun release(owner: Any)

    /** Once a tick: lease expiry. */
    fun tick()

    // ---- acquire, then move (what the features call)

    fun turn(owner: Any, p: AimPriority, yaw: Float, pitch: Float, tolerance: Float = 1.5f, overshoot: Boolean = true) =
        acquire(owner, p) && turnTo(owner, yaw, pitch, tolerance, overshoot)

    fun glideAs(owner: Any, p: AimPriority, yaw: Float, pitch: Float) = acquire(owner, p) && glide(owner, yaw, pitch)
    fun setViewAs(owner: Any, p: AimPriority, yaw: Float, pitch: Float) = acquire(owner, p) && setView(owner, yaw, pitch)
    fun nudgeAs(owner: Any, p: AimPriority, dYaw: Float, dPitch: Float) = acquire(owner, p) && nudgeView(owner, dYaw, dPitch)
}

/** What the broker drives: [Humanize] in the game, a [TurnEngine] on a fake view in tests. */
interface Turner {
    fun turnTo(yaw: Float, pitch: Float, tolerance: Float, overshoot: Boolean): Boolean
    fun glide(yaw: Float, pitch: Float)
    fun setView(yaw: Float, pitch: Float)
    fun nudgeView(dYaw: Float, dPitch: Float)
    fun resetTurnState()
}

class LeasedAimBroker(private val turner: Turner, val leaseTicks: Int = 40): AimBroker {
    override var holder: Any? = null
        private set
    var priority = AimPriority.IDLE
        private set

    /** Whose turn the engine's state belongs to (kept after a lease runs out, so the same owner carries on). */
    private var stateOwner: Any? = null
    private var now = 0L
    private var lastMove = 0L

    override fun acquire(owner: Any, p: AimPriority): Boolean {
        val h = holder
        if (h != null && h !== owner && p <= priority) return false
        if (h !== owner) {
            if (stateOwner !== owner) turner.resetTurnState()
            stateOwner = owner
            lastMove = now
        }
        holder = owner
        priority = p
        return true
    }

    private inline fun move(owner: Any, block: () -> Unit): Boolean {
        if (holder !== owner) return false
        lastMove = now
        block()
        return true
    }

    override fun turnTo(owner: Any, yaw: Float, pitch: Float, tolerance: Float, overshoot: Boolean): Boolean {
        if (holder !== owner) return false
        lastMove = now
        return turner.turnTo(yaw, pitch, tolerance, overshoot)
    }

    override fun glide(owner: Any, yaw: Float, pitch: Float) = move(owner) { turner.glide(yaw, pitch) }
    override fun setView(owner: Any, yaw: Float, pitch: Float) = move(owner) { turner.setView(yaw, pitch) }
    override fun nudgeView(owner: Any, dYaw: Float, dPitch: Float) = move(owner) { turner.nudgeView(dYaw, dPitch) }

    override fun release(owner: Any) {
        if (holder !== owner) return
        turner.resetTurnState()
        holder = null
        stateOwner = null
        priority = AimPriority.IDLE
    }

    override fun tick() {
        now++
        if (holder != null && now - lastMove >= leaseTicks) {
            // the lease ran out: nobody holds the view now. The turn state stays with this owner until someone else
            // takes the view (then it is reset), so a feature that pauses and comes back turns on as before.
            holder = null
            priority = AimPriority.IDLE
        }
    }
}

/** Humanize as the broker's [Turner]: the player's view. */
private object HumanizeTurner: Turner {
    private val player get() = net.minecraft.client.Minecraft.getInstance().player

    override fun turnTo(yaw: Float, pitch: Float, tolerance: Float, overshoot: Boolean) =
        player?.let { Humanize.turnTo(it, yaw, pitch, tolerance, overshoot) } ?: false

    override fun glide(yaw: Float, pitch: Float) { player?.let { Humanize.glide(it, yaw, pitch) } }
    override fun setView(yaw: Float, pitch: Float) { player?.let { Humanize.setView(it, yaw, pitch) } }
    override fun nudgeView(dYaw: Float, dPitch: Float) { player?.let { Humanize.nudgeView(it, dYaw, dPitch) } }
    override fun resetTurnState() = Humanize.resetTurnState()
}

/** The game's broker. Every automatic look goes through it. */
object Aim: AimBroker by LeasedAimBroker(HumanizeTurner), ISelfInit {
    override fun init() {
        EventBus.register<TickEvent.Start> { tick() }
    }
}
