package com.github.noamm9.features.impl.fishing

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
import com.github.noamm9.config.types.TextInputSetting

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

/** Simulates this throw: true when it comes down on water / lava (not a block on the way, not dry ground). */
internal fun AutoFishing.throwLandsInFluid(p: LocalPlayer, yaw: Float, pitch: Float): Boolean {
    // follow the throw tick by tick: water / lava first -> good; a block first -> it lands on the ground
    val lvl = p.level()
    val t = FishingMath.throwStart(yaw, pitch)
    var pos = Vec3(p.x + t.offX, p.eyeY, p.z + t.offZ)
    var v = Vec3(t.vx, t.vy, t.vz)
    repeat(160) {
        val next = pos.add(v)
        val hit = lvl.clip(net.minecraft.world.level.ClipContext(pos, next, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.ANY, p))
        if (hit.type == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            val bp = (hit as net.minecraft.world.phys.BlockHitResult).blockPos
            return wantedFluid(bp) || wantedFluid(BlockPos.containing(hit.location))
        }
        if (next.y < p.y - 30) return false
        pos = next
        v = Vec3(v.x * FishingMath.DRAG, v.y * FishingMath.DRAG - FishingMath.GRAVITY, v.z * FishingMath.DRAG)
    }
    return false
}

/** The "?" / countdown Hypixel floats over a bobber that's waiting in the water. */
internal fun AutoFishing.waitingMarker(hook: FishingHook): Boolean =
    com.github.noamm9.utils.FloatingText.near(hook.level(), hook.boundingBox.inflate(1.0, 2.5, 1.0))
        .any { (_, t) -> com.github.noamm9.utils.SkyblockText.WAITING_MARKER.matches(t.replace(Regex("§."), "").trim()) }

/**
 * Hypixel sometimes spawns our hook without linking it to the player (player.fishing stays null), which
 * would leave us waiting for a hook forever: find the hook we own nearby and link it back.
 */
internal fun AutoFishing.relinkBobber() {
    val p = mc.player ?: return
    if (p.fishing != null) return
    val own = p.level().getEntitiesOfClass(FishingHook::class.java, p.boundingBox.inflate(40.0)) { it.playerOwner === p && ! it.isRemoved }
        .minByOrNull { it.distanceToSqr(p) } ?: return
    p.fishing = own
    statRelink++
    journal.log("re-linked a hook the game hadn't linked")
}

internal fun AutoFishing.inFluid(hook: FishingHook) = waitingMarker(hook) || when (fluid.value) {
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
    if (com.github.noamm9.utils.FloatingText.any(hook.level(), hook.boundingBox.inflate(1.5, 3.0, 1.5), com.github.noamm9.utils.SkyblockText.BITE)) return true
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
    val t = FishingMath.throwStart(yaw, pitch) // vanilla: 0.6 / length + 0.5 (the 0.5 has a tiny random spread)
    var pos = Vec3(p.x + t.offX, p.eyeY, p.z + t.offZ)
    var v = Vec3(t.vx, t.vy, t.vz)
    val level = p.level()
    repeat(160) {
        val next = pos.add(v)
        val hit = level.clip(net.minecraft.world.level.ClipContext(pos, next, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, p))
        if (hit.type != net.minecraft.world.phys.HitResult.Type.MISS) return null
        if (next.y <= surface && v.y < 0) return next
        pos = next
        v = Vec3(v.x * FishingMath.DRAG, v.y * FishingMath.DRAG - FishingMath.GRAVITY, v.z * FishingMath.DRAG)
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

internal fun AutoFishing.aimAt(p: LocalPlayer, hs: Entity) {
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
    // Cast Fail (passed in as the profile's failure rate): the hand clearly slips, 2-4° off; a miss gets recast
    val profile = com.github.noamm9.utils.Humanize.profile().copy(failRate = castFail.value / 100.0)
    if (timing.fails(profile.failRate)) {
        val off = 2f + RANDOM.nextFloat() * 2f
        val a = RANDOM.nextFloat() * (2 * Math.PI).toFloat()
        wobYaw = kotlin.math.cos(a) * off
        wobPitch = kotlin.math.sin(a) * off * 0.6f
        journal.log("cast slip ${"%.1f".format(off)}°")
        return
    }
    // cast-to-cast variance: each offset leans on the last (Cast Carry-Over), pitch with its own spread
    castYawSeries = com.github.noamm9.utils.HumanMotion.carried(castYawSeries, w.toDouble(), castCarry.value, RANDOM)
    castPitchSeries = com.github.noamm9.utils.HumanMotion.carried(castPitchSeries, castPitchSpread.value, castCarry.value, RANDOM)
    wobYaw = castYawSeries.toFloat()
    wobPitch = castPitchSeries.toFloat()
}

/** Is the hook inside the hotspot? If not, move the aim point by the miss and solve the throw again (8 tries). */
internal fun AutoFishing.landedIn(p: LocalPlayer, hook: FishingHook, hs: Entity): Boolean {
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



// ------------------------------------------------------------------ timing

internal fun AutoFishing.rand(min: Int, max: Int) = if (max <= min) min else min + RANDOM.nextInt(max - min + 1)


/** Reel Assist's delay: mostly the lower third of [min, max], 8% of the time a slow reaction. */
internal fun AutoFishing.delay(min: Int, max: Int): Int {
    val lo = minOf(min, max)
    val hi = maxOf(min, max)
    if (hi <= lo) return lo
    if (timingModel.value == 0) return rand(lo, hi)
    if (timingModel.value == 2) {
        timing.slipChance = slipChance.value / 100.0
        timing.drift = drift.value / 100.0
        timing.fatiguePerHour = fatigue.value / 100.0
        return timing.next(lo, hi)
    }
    val span = (hi - lo).toDouble()
    val v = if (RANDOM.nextDouble() < 0.08) lo + span * (0.6 + 0.4 * RANDOM.nextDouble())
    else lo + span * 0.3 + RANDOM.nextGaussian() * span * 0.15
    return Math.round(v).toInt().coerceIn(lo, hi)
}


/** Reel delay: the Human model gets its own timing with the heavier Reel Spread tail. */
internal fun AutoFishing.reelDelay(): Int {
    if (timingModel.value != 2) return delay(reelMin.value, reelMax.value)
    reelTiming.spread = reelSpread.value
    reelTiming.slipChance = slipChance.value / 100.0
    reelTiming.drift = drift.value / 100.0
    reelTiming.fatiguePerHour = fatigue.value / 100.0
    reelTiming.tick()
    return reelTiming.next(reelMin.value, reelMax.value)
}


/** Ticks to the next attack click: log-normal around [median] (Click Spread), never the same gap twice in a row. */
internal fun AutoFishing.clickGap(median: Int): Int {
    lastClickGap = com.github.noamm9.utils.HumanMotion.clickInterval(median.toDouble(), com.github.noamm9.features.impl.general.HumanBehaviour.clickSpread.value, RANDOM, lastClickGap)
    return lastClickGap
}


/** The profile for attack clicks: Human Behaviour's, with Click Miss as its failure rate. */
internal fun AutoFishing.clickProfile() = com.github.noamm9.utils.Humanize.profile().copy(failRate = com.github.noamm9.features.impl.general.HumanBehaviour.clickMiss.value / 100.0)


/** True when this attack click doesn't go through ([profile]'s failure rate); tried again after a short gap. */
internal fun AutoFishing.clickMissed(profile: com.github.noamm9.utils.HumanProfile = clickProfile()): Boolean {
    if (! com.github.noamm9.utils.HumanMotion.chance(profile.failRate * 100, RANDOM)) return false
    hitCooldown = com.github.noamm9.utils.HumanMotion.between(1, 3, RANDOM)
    journal.log("click miss")
    return true
}


/** Wait before the next cast: the timing model, plus reading the catch and the odd short break. */
internal fun AutoFishing.castWait(): Int {
    var t = delay(castMin.value, castMax.value)
    if (lookAtCatch.value && justCaughtSomething) t += timing.uniform(8, 25)
    justCaughtSomething = false
    t += timing.maybeBreak(breakChance.value, 40, 120)
    if (RANDOM.nextInt(100) < hesitate.value) t += timing.uniform(6, 20)
    if (com.github.noamm9.utils.HumanMotion.chance(zoneOut.value, RANDOM)) {
        val z = com.github.noamm9.utils.HumanMotion.between((zoneOutMin.value * 20).toInt(), (zoneOutMax.value * 20).toInt(), RANDOM)
        t += z
        journal.log("zone out ${z / 20.0}s")
    }
    return t
}


internal fun AutoFishing.nextMoveDelay() = (jitterEvery.value * 20 * (0.8 + 0.4 * RANDOM.nextDouble())).toInt()


// ------------------------------------------------------------------ turning

internal fun AutoFishing.wrap(d: Float): Float {
    var x = d % 360f
    if (x > 180f) x -= 360f
    if (x < - 180f) x += 360f
    return x
}


/** Eased turn towards yaw / pitch. True once within [tol] degrees. */
internal fun AutoFishing.turn(p: LocalPlayer, yaw: Float, pitch: Float, tol: Float = 1.5f): Boolean {
    val dy = wrap(yaw - p.yRot)
    val dp = pitch - p.xRot
    if (abs(dy) <= tol && abs(dp) <= tol) return true
    if (! smoothTurns.value) {
        com.github.noamm9.utils.Humanize.setView(p, yaw, pitch)
        return true
    }
    // no overshoot while fighting: a creature on the move needs the aim to stay on it
    return com.github.noamm9.utils.Humanize.turnTo(p, yaw, pitch, tol, overshoot = state != FishingState.FIGHT)
}


/** The small eased head movements around the bobber while waiting. Also follows the player's own camera moves. */
internal fun AutoFishing.lookMovement(p: LocalPlayer) {
    if (! lookJitter.value) return
    val expYaw = anchorYaw + offYaw + wobYaw
    val expPitch = anchorPitch + offPitch + wobPitch
    if (abs(wrap(p.yRot - expYaw)) > 0.3f || abs(p.xRot - expPitch) > 0.3f) {
        // the player turned the camera: fish where they look now
        anchorYaw = p.yRot - offYaw - wobYaw
        anchorPitch = p.xRot - offPitch - wobPitch
    }
    if (moveLen > 0) {
        moveTick++
        val t = (moveTick / moveLen.toFloat()).coerceAtMost(1f)
        val ease = t * t * (3 - 2 * t)
        offYaw = fromYaw + (toYaw - fromYaw) * ease
        offPitch = fromPitch + (toPitch - fromPitch) * ease
        com.github.noamm9.utils.Humanize.glide(p, anchorYaw + offYaw + wobYaw, (anchorPitch + offPitch + wobPitch).coerceIn(- 90f, 90f))
        if (t >= 1f) moveLen = 0
        return
    }
    if (--nextMoveIn > 0) return
    // a bounded random walk around the spot: each move is a small step from the last, never past Drift Range
    val step = jitterDegrees.value.toFloat()
    val bound = driftRange.value.toFloat()
    fromYaw = offYaw; fromPitch = offPitch
    toYaw = com.github.noamm9.utils.HumanMotion.walk(offYaw, step, bound, RANDOM)
    toPitch = com.github.noamm9.utils.HumanMotion.walk(offPitch, step * 0.6f, bound * 0.6f, RANDOM)
    moveTick = 0
    moveLen = rand(6, 14)
    nextMoveIn = nextMoveDelay()
}


// ------------------------------------------------------------------ main loop

internal fun AutoFishing.rod(): Int? {
    if (rodSlot.value in 1 .. 9) return rodSlot.value - 1
    return PlayerUtils.findHotbarSlot { it.item == Items.FISHING_ROD }
}


internal fun AutoFishing.hold(slot: Int) {
    val p = mc.player ?: return
    if (p.inventory.selectedSlot != slot) p.inventory.selectedSlot = slot
    expectedSlot = slot
}


internal fun AutoFishing.use(p: LocalPlayer) {
    mc.gameMode?.useItem(p, InteractionHand.MAIN_HAND)
    p.swing(InteractionHand.MAIN_HAND)
}

//#endif
