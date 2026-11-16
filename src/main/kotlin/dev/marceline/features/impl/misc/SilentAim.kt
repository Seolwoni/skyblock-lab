package dev.marceline.features.impl.misc

import dev.marceline.Marceline.mc
import dev.marceline.config.types.DropdownSetting
import dev.marceline.config.types.SliderSetting
import dev.marceline.event.impl.RenderWorldEvent
import dev.marceline.event.impl.TickEvent
import dev.marceline.event.impl.WorldChangeEvent
import dev.marceline.features.Feature
import dev.marceline.utils.Aim
import dev.marceline.utils.AimPriority
import dev.marceline.utils.HumanMotion
import kotlin.math.abs

/**
 * Silent Aim: while an automatic feature aims, your screen stays where you were looking (the mouse still turns
 * it); only the character's view - what the server and other players see - turns, through Humanize as always.
 *
 * - ON: an aim in scope holds the view. The camera is Free Look's, in first person.
 * - RETURN: the aim let go - the character's view turns back to the camera through [Aim] (IDLE), then it ends.
 * - CATCH_UP: an aim out of scope took over (e.g. Auto Fishing going back to casting) - the camera eases onto the
 *   character's view (on screen only), then it ends and that aim is seen as usual.
 */
object SilentAim: Feature("While an automatic feature aims, your screen doesn't move: only your character's view turns (smoothly, as always). The mouse keeps turning your screen; when the aim lets go, your character turns back to where you're looking.") {
    private val scope by DropdownSetting("Use For", 0, listOf("Fishing Combat", "All Automatic Aim")).withDescription(
        "Fishing Combat: only Auto Fishing's fights. All Automatic Aim: anything that turns your view (fishing, routes, dungeon features)")
    private val catchUp by SliderSetting("Camera Catch-up", 8.0, 2.0, 20.0, 0.5, "x").withDescription(
        "How fast your screen eases onto your character's view when a silent aim hands over to one that isn't (screen only)")

    private enum class Phase { OFF, ON, RETURN, CATCH_UP }
    private var phase = Phase.OFF
    private var lastNs = 0L

    /** Whether this owner at this priority aims silently. */
    private fun inScope(owner: Any?, p: AimPriority): Boolean {
        if (owner == null || owner === this) return false
        return when (scope.value) {
            0 -> owner.javaClass.simpleName == "AutoFishing" && p >= AimPriority.COMBAT
            else -> true
        }
    }

    override fun init() {
        Aim.onTake = { owner, p -> onTake(owner, p) }
        register<TickEvent.Start> { tick() }
        register<RenderWorldEvent> { frame() }
        register<WorldChangeEvent> { stop() }
    }

    override fun onDisable() {
        super.onDisable()
        Aim.release(this)
        stop()
    }

    /** Before the new aim's first move: take the camera off the view so the screen stays put. */
    private fun onTake(owner: Any, p: AimPriority) {
        if (! enabled || owner === this) return
        val pl = mc.player ?: return
        if (! inScope(owner, p)) return
        when (phase) {
            Phase.OFF -> if (FreeLook.beginSilent(pl.yRot, pl.xRot)) phase = Phase.ON
            else -> phase = Phase.ON
        }
    }

    private fun tick() {
        if (phase == Phase.OFF) return
        if (! enabled || mc.player == null) return stop()
        val h = Aim.holder
        val pr = Aim.holderPriority
        when (phase) {
            Phase.ON -> when {
                h == null -> phase = Phase.RETURN
                ! inScope(h, pr) -> phase = Phase.CATCH_UP
            }
            Phase.RETURN -> {
                if (h != null && h !== this) { phase = if (inScope(h, pr)) Phase.ON else Phase.CATCH_UP; return }
                // the character turns back to where the screen is, like any other turn
                if (Aim.turn(this, AimPriority.IDLE, FreeLook.yaw, FreeLook.pitch, 0.5f, overshoot = false)) {
                    Aim.release(this)
                    stop()
                }
            }
            else -> Unit
        }
    }

    /** CATCH_UP: the screen eases onto the character's view every frame. */
    private fun frame() {
        val now = System.nanoTime()
        val dt = ((now - lastNs) / 1e9f).coerceIn(0f, 0.05f)
        lastNs = now
        if (phase != Phase.CATCH_UP) return
        val p = mc.player ?: return stop()
        val k = (dt * catchUp.value.toFloat()).coerceAtMost(1f)
        val dy = HumanMotion.wrapDeg(p.yRot - FreeLook.yaw)
        val dp = p.xRot - FreeLook.pitch
        if (abs(dy) < 0.3f && abs(dp) < 0.3f) return stop()
        FreeLook.yaw += dy * k
        FreeLook.pitch += dp * k
    }

    private fun stop() {
        if (phase == Phase.OFF) return
        phase = Phase.OFF
        FreeLook.endSilent()
    }
}
