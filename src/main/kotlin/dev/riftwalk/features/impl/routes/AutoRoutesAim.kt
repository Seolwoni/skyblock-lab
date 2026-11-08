package dev.riftwalk.features.impl.routes

//#if CHEAT
import dev.riftwalk.Riftwalk.mc
import dev.riftwalk.config.types.DropdownSetting
import dev.riftwalk.config.types.KeybindSetting
import dev.riftwalk.config.types.SliderSetting
import dev.riftwalk.config.types.ToggleSetting
import dev.riftwalk.event.impl.RenderWorldEvent
import dev.riftwalk.event.impl.TickEvent
import dev.riftwalk.event.impl.WorldChangeEvent
import dev.riftwalk.features.Feature
import dev.riftwalk.utils.ChatUtils.modMessage
import dev.riftwalk.utils.MathUtils
import dev.riftwalk.utils.PlayerUtils
import dev.riftwalk.utils.items.EtherwarpHelper
import dev.riftwalk.utils.items.ItemUtils.skyblockId
import dev.riftwalk.utils.render.Render2D.drawString
import dev.riftwalk.utils.render.world.Render3D.renderBlock
import dev.riftwalk.utils.render.world.Render3D.renderLine
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.awt.Color
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ambient.Bat
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import xyz.yourboykyle.secretroutes.Main
import xyz.yourboykyle.secretroutes.dungeons.detection.DungeonScanner
import xyz.yourboykyle.secretroutes.utils.RoomDirectionUtils
import xyz.yourboykyle.secretroutes.utils.RoomRotationUtils
import xyz.yourboykyle.secretroutes.utils.RotationUtils

// Turning and using items: eased turns (Humanize), aim-then-use, sending the rotation.

/** Rotate now, click on a later tick (the server needs the new rotation first). */
/** Aims at [target], then uses / hits it. Returns false while still turning (call again next tick). */
internal fun AutoRoutes.aimThen(target: Vec3, use: Boolean): Boolean {
    releaseMovement()
    if (! turnTo(target)) return false
    if (zeroTick) {
        sendRotation()
        if (use) PlayerUtils.rightClick() else PlayerUtils.leftClick()
        return true
    }
    actionUse = use
    phase = AutoRoutes.Phase.ACTION_AIM
    wait = actionDelay
    return true
}

/** Snaps, or with Smooth Turning turns like Assist Core does. True once looking within [tolerance] degrees. */
internal fun AutoRoutes.turn(yaw: Float, pitch: Float, tolerance: Float = 2f, settle: Boolean = true): Boolean {
    val p = mc.player ?: return false
    lastTurnTick = moveTick
    lastDriveTick = moveTick
    val arrived = if (smoothTurning.value) dev.riftwalk.utils.Humanize.turnTo(p, yaw, pitch, tolerance)
    else {
        dev.riftwalk.utils.Humanize.setView(p, yaw, pitch)
        true
    }
    if (! arrived) return false // keep the settle state: chasing a moving aim must not restart the pause
    if (! settle || aimPause.value <= 0) return true

    // The same aim (within 12 degrees, so a moving mob or a knock-back doesn't count as new) waits once.
    val prev = settleKey
    val same = prev != null && abs(wrapDeg(yaw - prev.first)) < 12f && abs(pitch - prev.second) < 12f
    if (! same) {
        settleKey = yaw to pitch
        settleLeft = dev.riftwalk.utils.Humanize.around(aimPause.value)
    }
    if (settleLeft > 0) {
        settleLeft--
        return false
    }
    return true
}

internal fun AutoRoutes.wrapDeg(d: Float): Float {
    var x = d % 360f
    if (x > 180f) x -= 360f
    if (x < - 180f) x += 360f
    return x
}

internal fun AutoRoutes.turnTo(target: Vec3, tolerance: Float = 2f, settle: Boolean = true): Boolean {
    val rot = MathUtils.calcYawPitch(dev.riftwalk.utils.Humanize.aimPoint(target))
    return turn(rot.yaw, rot.pitch, tolerance, settle)
}

/** 0-Tick: tell the server the new look direction right away, so a click in this same tick uses it. */
internal fun AutoRoutes.sendRotation() {
    val p = mc.player ?: return
    mc.connection?.send(ServerboundMovePlayerPacket.Rot(p.yRot, p.xRot, p.onGround(), p.horizontalCollision))
}

/** 0-Tick right click with the held item (etherwarp, pearl): rotation packet first, then the use packet. */
internal fun AutoRoutes.useNow() {
    val p = mc.player ?: return
    sendRotation()
    mc.gameMode?.useItem(p, InteractionHand.MAIN_HAND)
    p.swing(InteractionHand.MAIN_HAND)
}

//#endif
