package com.github.noamm9.features.impl.misc

//#if CHEAT
import com.github.noamm9.NoammAddons.mc
import com.github.noamm9.config.types.DropdownSetting
import com.github.noamm9.config.types.KeybindSetting
import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.MainThreadPacketReceivedEvent
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.features.impl.routes.AssistBridge
import com.github.noamm9.features.impl.routes.AutoRoutes
import com.github.noamm9.ui.notification.NotificationManager
import com.github.noamm9.utils.ChatUtils.modMessage
import com.github.noamm9.utils.ColorUtils.withAlpha
import com.github.noamm9.utils.GuiUtils
import com.github.noamm9.utils.MathUtils
import com.github.noamm9.utils.PlayerUtils
import com.github.noamm9.utils.items.ItemUtils.skyblockId
import com.github.noamm9.utils.render.Render2D.drawString
import java.util.Random
import kotlin.math.abs
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.tags.FluidTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.projectile.FishingHook
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3

// Staying safe: standing in the middle of a safe block, Hyperion blasts that can't land in lava, getting out of lava.

/** In lava: jump and walk back to the last safe ground; stop if it doesn't work out. */
internal fun AutoFishing.escapeLava(p: LocalPlayer) {
    ownMoveTicks = 20
    if (p.fishing != null) rod()?.let { hold(it); use(p) }
    val back = lastSafe
    status = "in lava, getting out"
    lavaTicks++
    mc.options.keyJump.isDown = true
    mc.options.keySprint.isDown = true
    if (back != null) {
        // aim at the last safe spot, a little above it so a teleport lands on top
        val target = back.add(0.0, 1.2, 0.0)
        val rot = MathUtils.calcYawPitch(target)
        PlayerUtils.rotate(rot.yaw, rot.pitch)
        mc.options.keyUp.isDown = true
        // swimming alone often can't climb out: teleport back with AOTV, else the Hyperion, every half second
        if (lavaTicks % 10 == 4) {
            val tp = PlayerUtils.findHotbarSlot { it.skyblockId == "ASPECT_OF_THE_VOID" || it.skyblockId == "ASPECT_OF_THE_END" }
                ?: PlayerUtils.findHotbarSlot { it.skyblockId in WITHER_BLADES }
            if (tp != null) { hold(tp); use(p) }
        }
    }
    if (lavaTicks > 200) {
        stopWalking()
        failStop("fell into lava and couldn't get out")
        return
    }
    state = State.TRAVEL
    repath = 0
}

/** A block you can safely stand in: solid, non-lava ground under it, room for the body, no lava in it. */
internal fun AutoFishing.safeStand(lvl: net.minecraft.world.level.Level, b: BlockPos): Boolean {
    fun solid(x: BlockPos) = ! lvl.getBlockState(x).getCollisionShape(lvl, x).isEmpty
    fun lava(x: BlockPos) = lvl.getFluidState(x).`is`(FluidTags.LAVA)
    val under = b.below()
    return solid(under) && ! lava(under) && ! solid(b) && ! solid(b.above()) && ! lava(b) && ! lava(b.above())
}

/**
 * Keeps us in the exact middle of a safe block. Moves with WASD relative to where we're already looking
 * (no turning), sneaking so it can never walk off an edge. True once we're there.
 */
internal fun AutoFishing.centered(p: LocalPlayer): Boolean {
    if (! standCentre.value || ! p.onGround()) return true
    val lvl = p.level()
    val here = BlockPos.containing(p.position())
    var target = centreTarget
    if (target == null || BlockPos.containing(target).distManhattan(here) > 2) {
        val pick = (listOf(here) + listOf(here.north(), here.south(), here.east(), here.west(),
            here.north().east(), here.north().west(), here.south().east(), here.south().west()))
            .filter { safeStand(lvl, it) }
            .minByOrNull { Vec3.atBottomCenterOf(it).distanceToSqr(p.position()) }
            ?: return true // nowhere better nearby: stay
        target = Vec3.atBottomCenterOf(pick)
        centreTarget = target
    }
    val dx = target.x - p.x
    val dz = target.z - p.z
    if (dx * dx + dz * dz < 0.1 * 0.1 || ++centreTicks > 100) {
        releaseCentre()
        centreTicks = 0
        return true
    }
    val yr = Math.toRadians(p.yRot.toDouble())
    val f = dx * - kotlin.math.sin(yr) + dz * kotlin.math.cos(yr) // along where we look
    val r = dx * - kotlin.math.cos(yr) + dz * - kotlin.math.sin(yr) // to our right
    ownMoveTicks = 20
    mc.options.keyShift.isDown = true
    mc.options.keyUp.isDown = f > 0.05
    mc.options.keyDown.isDown = f < - 0.05
    mc.options.keyRight.isDown = r > 0.05
    mc.options.keyLeft.isDown = r < - 0.05
    return false
}

internal fun AutoFishing.releaseCentre() {
    mc.options.keyShift.isDown = false
    mc.options.keyUp.isDown = false
    mc.options.keyDown.isDown = false
    mc.options.keyRight.isDown = false
    mc.options.keyLeft.isDown = false
}

/**
 * True when a Wither Impact with this look can't drop us into lava: follows the 10-block teleport until
 * something blocks the body, then checks there's solid ground (not lava) under where we'd end up.
 */
internal fun AutoFishing.blastSafe(p: LocalPlayer, yaw: Float, pitch: Float): Boolean {
    val lvl = p.level()
    val look = Vec3.directionFromRotation(pitch, yaw)
    val eyeH = p.eyeHeight.toDouble()
    fun solid(b: BlockPos) = ! lvl.getBlockState(b).getCollisionShape(lvl, b).isEmpty
    var end = p.position()
    var d = 0.25
    while (d <= 10.0) {
        val feet = p.eyePosition.add(look.scale(d)).subtract(0.0, eyeH, 0.0)
        val bp = BlockPos.containing(feet)
        if (solid(bp) || solid(bp.above())) break
        end = feet
        d += 0.25
    }
    val at = BlockPos.containing(end)
    if (lvl.getFluidState(at).`is`(FluidTags.LAVA) || lvl.getFluidState(at.above()).`is`(FluidTags.LAVA)) return false
    for (dy in 1 .. 4) {
        val b = at.below(dy - 1).below()
        if (lvl.getFluidState(b).`is`(FluidTags.LAVA)) return false
        if (solid(b)) return true
    }
    return false // nothing under us: we'd fall
}

//#endif
