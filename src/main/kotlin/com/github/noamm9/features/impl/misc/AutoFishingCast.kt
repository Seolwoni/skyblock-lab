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

// The cast: the throw solver (where the hook comes down), aiming at a Hotspot, and reading the hook (fluid, ground, bite).

internal fun AutoFishing.wantsLava(hook: FishingHook?) = when (fluid.value) {
    1 -> false
    2 -> true
    else -> hook != null && (hook.isInLava || nearFluid(hook, FluidTags.LAVA))
}

internal fun AutoFishing.fluidName() = if (wantsLava(mc.player?.fishing)) "lava" else "water"

/** The bobber floats on top, so its own in-water flag flickers: look at its block and the two below too. */
internal fun AutoFishing.nearFluid(hook: FishingHook, tag: net.minecraft.tags.TagKey<net.minecraft.world.level.material.Fluid>): Boolean {
    // the hook can sit a little above, beside or under the surface (lava especially): look around it
    val lvl = hook.level()
    val pos = hook.blockPosition()
    for (dy in 1 downTo - 3) for (dx in - 1 .. 1) for (dz in - 1 .. 1) {
        if (dy == 1 && (dx != 0 || dz != 0)) continue
        if ((dx != 0 || dz != 0) && dy < - 1) continue
        if (lvl.getFluidState(pos.offset(dx, dy, dz)).`is`(tag)) return true
    }
    return false
}

internal fun AutoFishing.inFluid(hook: FishingHook) = when (fluid.value) {
    1 -> hook.isInWater || nearFluid(hook, FluidTags.WATER)
    2 -> hook.isInLava || nearFluid(hook, FluidTags.LAVA)
    else -> hook.isInWater || hook.isInLava || nearFluid(hook, FluidTags.WATER) || nearFluid(hook, FluidTags.LAVA)
}

/** True once the hook has clearly settled somewhere that isn't water / lava. */
internal fun AutoFishing.grounded(hook: FishingHook): Boolean {
    // once it has been in the water / lava this cast, it didn't land on the ground; and a hook stuck in
    // a mob (or SkyBlock's invisible stands over lava) isn't on the ground either
    if (inFluidTicks >= 3 || hook.hookedIn != null || inFluid(hook)) {
        groundTicks = 0
        return false
    }
    val moved = lastHookPos?.distanceToSqr(hook.position()) ?: 1.0
    lastHookPos = hook.position()
    if (timer < 25) return false
    val stuck = hook.onGround() || moved < 1.0E-4
    groundTicks = if (stuck) groundTicks + 1 else 0
    return groundTicks >= 10
}

/** SkyBlock puts a "!!!" stand over the hook on a bite (water and lava); vanilla pulls the hook down. */
internal fun AutoFishing.bitten(hook: FishingHook): Boolean {
    // the server's "!!!" over the bobber: an armor stand name (older) or a text display (newer Hypixel)
    if (com.github.noamm9.utils.FloatingText.any(hook.level(), hook.boundingBox.inflate(1.5, 3.0, 1.5), "!!!")) return true
    return hookDive.value && inFluidTicks > 10 && hook.deltaMovement.y < - 0.05
}

/** The fluid surface height at (or just below) [pos]. */
internal fun AutoFishing.surfaceY(pos: BlockPos): Double {
    for (dy in 2 downTo - 6) {
        val b = pos.offset(0, dy, 0)
        if (isFluid(b) && ! isFluid(b.above())) return b.y + 0.88
    }
    return pos.y.toDouble()
}

/**
 * Where a hook cast from here with this yaw / pitch comes down on a surface at [surface] (vanilla throw:
 * 0.6 blocks/tick along the look, 0.92 drag, 0.03 gravity). Null if it hits a block on the way.
 */
internal fun AutoFishing.landing(p: LocalPlayer, yaw: Float, pitch: Float, surface: Double): Vec3? {
    val r = Math.PI / 180.0
    val f2 = kotlin.math.cos(- yaw * r - Math.PI)
    val f3 = kotlin.math.sin(- yaw * r - Math.PI)
    val f4 = - kotlin.math.cos(- pitch * r)
    val f5 = kotlin.math.sin(- pitch * r)
    var pos = Vec3(p.x - f3 * 0.3, p.eyeY, p.z - f2 * 0.3)
    var v = Vec3(- f3, (- (f5 / f4)).coerceIn(- 5.0, 5.0), - f2)
    v = v.scale(0.6 / v.length() + 0.5) // vanilla: 0.6 / length + 0.5 (the 0.5 has a tiny random spread)
    val level = p.level()
    repeat(160) {
        val next = pos.add(v)
        val hit = level.clip(net.minecraft.world.level.ClipContext(pos, next, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, p))
        if (hit.type != net.minecraft.world.phys.HitResult.Type.MISS) return null
        if (next.y <= surface && v.y < 0) return next
        pos = next
        v = Vec3(v.x * 0.92, v.y * 0.92 - 0.03, v.z * 0.92)
    }
    return null
}

/** Finds the yaw / pitch whose throw lands on [target]. */
internal fun AutoFishing.solveAim(p: LocalPlayer, target: Vec3): Pair<Float, Float>? {
    val surface = surfaceY(BlockPos.containing(target))
    val yaw = Math.toDegrees(kotlin.math.atan2(target.z - p.z, target.x - p.x)).toFloat() - 90f
    val tries = ArrayList<Pair<Float, Double>>()
    var pitch = - 55f
    while (pitch <= 50f) {
        landing(p, yaw, pitch, surface)?.let { tries += pitch to horizontalDist(it, target) }
        pitch += 0.25f
    }
    val bestErr = tries.minOfOrNull { it.second } ?: return null
    // a short and a high throw can land on the same spot: take the flatter one, like people do
    val best = tries.filter { it.second <= bestErr + 0.25 }.maxByOrNull { it.first } ?: return null
    return yaw to best.first
}

internal fun AutoFishing.aimAt(p: LocalPlayer, hs: ArmorStand) {
    aimTarget = Vec3(hs.x, hs.y, hs.z)
    aimTries = 0
    val sol = solveAim(p, aimTarget!!)
    if (sol != null) {
        anchorYaw = sol.first
        anchorPitch = sol.second
    }
    else {
        val rot = MathUtils.calcYawPitch(hs.position())
        anchorYaw = rot.yaw
        anchorPitch = (rot.pitch - 8f).coerceIn(- 30f, 60f)
    }
    nextCastAim(p)
}

/** This cast's aim: the solved aim plus a small hand wobble. */
internal fun AutoFishing.nextCastAim(@Suppress("UNUSED_PARAMETER") p: LocalPlayer) {
    val w = aimWobble.value.toFloat()
    wobYaw = (RANDOM.nextFloat() * 2 - 1) * w
    wobPitch = (RANDOM.nextFloat() * 2 - 1) * w * 0.6f
}

/** Is the hook inside the hotspot? If not, move the aim point by the miss and solve the throw again (8 tries). */
internal fun AutoFishing.landedIn(p: LocalPlayer, hook: FishingHook, hs: ArmorStand): Boolean {
    val off = hook.position().subtract(hs.position())
    if (kotlin.math.sqrt(off.x * off.x + off.z * off.z) <= hotspotRadius.value || aimTries >= 8) return true
    aimTries++
    val base = aimTarget ?: Vec3(hs.x, hs.y, hs.z)
    aimTarget = Vec3(base.x - off.x * 0.85, base.y, base.z - off.z * 0.85)
    solveAim(p, aimTarget!!)?.let { (y, pi) -> anchorYaw = y; anchorPitch = pi }
    nextCastAim(p)
    returning = true
    status = "hook landed beside the hotspot, re-aiming ($aimTries)"
    return false
}

//#endif
