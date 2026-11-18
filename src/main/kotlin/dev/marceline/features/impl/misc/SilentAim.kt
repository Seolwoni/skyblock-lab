package dev.marceline.features.impl.misc

import dev.marceline.Marceline.mc
import dev.marceline.config.types.DropdownSetting
import dev.marceline.config.types.SliderSetting
import dev.marceline.config.types.ToggleSetting
import dev.marceline.event.impl.PacketEvent
import dev.marceline.event.impl.RenderWorldEvent
import dev.marceline.event.impl.TickEvent
import dev.marceline.event.impl.WorldChangeEvent
import dev.marceline.features.Feature
import dev.marceline.utils.Aim
import dev.marceline.utils.AimPriority
import dev.marceline.utils.HumanMotion
import dev.marceline.utils.Humanize
import dev.marceline.utils.SkyblockText
import dev.marceline.utils.items.ItemUtils.skyblockId
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.entity.player.Player
import kotlin.math.abs

/**
 * Silent Aim.
 *
 * - Free Camera While Fishing: while Auto Fishing runs, the mouse turns only your screen. Your character - what the
 *   server and everyone around see - keeps fishing with its own smooth turns. When fishing stops, the screen eases
 *   back onto the character.
 * - Silent Hyperion: every Wither Impact (your click, Auto Hyperion or Auto Fishing) goes to the server looking
 *   straight down, while your screen and your character keep looking wherever you are. The use packet is sent again
 *   with the down angles (Humanize.silentUse) and the real view goes out with the next movement.
 * - Auto Hyperion: with a wither blade in hand, it casts by itself whenever enemies are in reach.
 * - Lock Screen (the old mode, off by default): while an automatic aim turns the character, the screen stays put.
 */
object SilentAim: Feature("Look around freely while Auto Fishing runs: your screen follows your mouse, while everyone else sees your character fishing with its usual smooth turns.") {
    private val freeFish by ToggleSetting("Free Camera While Fishing", true).withDescription(
        "While Auto Fishing runs, the mouse turns only your screen; your character keeps fishing (and looking down to cast the Hyperion) as everyone sees it")
    private val silentHype by ToggleSetting("Silent Hyperion", false).withDescription(
        "Every Wither Impact is sent looking straight down without the character turning at all (your head doesn't move for others either)")
    private val autoHype by ToggleSetting("Auto Hyperion", true).withDescription(
        "With a Hyperion (or Astraea, Scylla, Valkyrie) in hand, casts Wither Impact by itself when enemies are in range")
    private val range by SliderSetting("Range", 6.0, 3.0, 10.0, 0.5, " blocks").withDescription("How close an enemy has to be").showIf { autoHype.value }
    private val castDelay by SliderSetting("Cast Delay", 6, 2, 20, 1, " ticks").withDescription(
        "About how long between casts (each wait a little different, through Human Behaviour)").showIf { autoHype.value }
    private val lockScreen by ToggleSetting("Lock Screen While Auto Aim", false).withDescription(
        "While an automatic feature turns your character (fishing, routes...), your screen stays where you were looking")
    private val scope by DropdownSetting("Use For", 0, listOf("Fishing Combat", "All Automatic Aim")).withDescription(
        "Fishing Combat: only Auto Fishing's fights. All Automatic Aim: anything that turns your view").showIf { lockScreen.value }
    private val catchUp by SliderSetting("Camera Catch-up", 8.0, 2.0, 20.0, 0.5, "x").withDescription(
        "How fast your screen eases onto your character's view when a locked aim hands over to one that isn't").showIf { lockScreen.value }

    /** Auto Fishing is running (it calls [claim] every tick): Auto Hyperion leaves the casting to it. */
    private var claimed = 0
    private var resending = false
    private var nextCast = 0
    private var ticks = 0
    private val rnd = java.util.Random()

    @JvmStatic fun claim() { claimed = 2 }

    /** Whether Wither Impact goes out silently (Auto Fishing then skips looking down). */
    @JvmStatic fun silentHyperion() = enabled && silentHype.value

    private fun holdsWitherBlade() = mc.player?.mainHandItem?.skyblockId in SkyblockText.WITHER_BLADES

    // ---- lock screen state
    private enum class Phase { OFF, ON, RETURN, CATCH_UP, FISH }
    private var phase = Phase.OFF
    private var lastNs = 0L

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

        // a Wither Impact about to go out with the real view: send it again looking down
        register<PacketEvent.Sent> {
            if (resending || ! silentHyperion()) return@register
            val pk = event.packet as? ServerboundUseItemPacket ?: return@register
            if (pk.hand != InteractionHand.MAIN_HAND || ! holdsWitherBlade() || abs(pk.xRot - 90f) < 0.5f) return@register
            val p = mc.player ?: return@register
            event.cancel()
            resending = true
            try { Aim.silentUse(p, pk.hand, pk.sequence, p.yRot, 90f) } finally { resending = false }
        }
    }

    override fun onDisable() {
        super.onDisable()
        Aim.release(this)
        stop()
    }

    private fun tick() {
        ticks++
        if (claimed > 0) claimed--
        autoCast()
        if (fishTick()) return
        lockTick()
    }

    /** Auto Hyperion: enemies in range and a wither blade in hand -> cast (silently when Silent Hyperion is on). */
    private fun autoCast() {
        if (! enabled || ! autoHype.value || claimed > 0 || mc.gui.screen() != null) return
        val p = mc.player ?: return
        val gm = mc.gameMode ?: return
        if (! holdsWitherBlade() || ticks < nextCast) return
        val r = range.value
        val near = p.level().getEntitiesOfClass(LivingEntity::class.java, p.boundingBox.inflate(r)) {
            it is Enemy && it !is Player && it !is ArmorStand && it.isAlive && it.health > 0f && it.distanceTo(p) <= r
        }
        if (near.isEmpty()) return
        nextCast = ticks + Humanize.around(castDelay.value)
        // a missed click now and then: Human Behaviour's profile with Click Miss as its failure rate
        val prof = Humanize.profile().copy(failRate = dev.marceline.features.impl.general.HumanBehaviour.clickMiss.value / 100.0)
        if (HumanMotion.chance(prof.failRate * 100, rnd)) return
        gm.useItem(p, InteractionHand.MAIN_HAND)
        p.swing(InteractionHand.MAIN_HAND)
    }

    /** Free Camera While Fishing. True while it has the camera. */
    private fun fishTick(): Boolean {
        val want = enabled && freeFish.value && claimed > 0 && mc.player != null
        if (! want) {
            if (phase == Phase.FISH) phase = Phase.CATCH_UP // fishing stopped: the screen eases back onto the character
            return false
        }
        when (phase) {
            Phase.FISH -> Unit
            Phase.OFF -> { val pl = mc.player!!; if (FreeLook.beginSilent(pl.yRot, pl.xRot)) phase = Phase.FISH }
            else -> { Aim.release(this); phase = Phase.FISH } // the camera is already ours: keep it where it is
        }
        return true
    }

    // ---- lock screen (the old Silent Aim)

    private fun onTake(owner: Any, p: AimPriority) {
        if (! enabled || ! lockScreen.value || owner === this || phase == Phase.FISH) return
        val pl = mc.player ?: return
        if (! inScope(owner, p)) return
        when (phase) {
            Phase.OFF -> if (FreeLook.beginSilent(pl.yRot, pl.xRot)) phase = Phase.ON
            else -> phase = Phase.ON
        }
    }

    private fun lockTick() {
        if (phase == Phase.OFF) return
        if (! enabled || ! lockScreen.value || mc.player == null) return stop()
        val h = Aim.holder
        val pr = Aim.holderPriority
        when (phase) {
            Phase.ON -> when {
                h == null -> phase = Phase.RETURN
                ! inScope(h, pr) -> phase = Phase.CATCH_UP
            }
            Phase.RETURN -> {
                if (h != null && h !== this) { phase = if (inScope(h, pr)) Phase.ON else Phase.CATCH_UP; return }
                if (Aim.turn(this, AimPriority.IDLE, FreeLook.yaw, FreeLook.pitch, 0.5f, overshoot = false)) {
                    Aim.release(this)
                    stop()
                }
            }
            else -> Unit
        }
    }

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
