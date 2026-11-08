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

// Moving along a route: walking, collecting a secret, re-syncing, etherwarp / AOTV leaps.

internal fun AutoRoutes.move(s: AutoRoutes.Step, pos: Vec3) {
    val eye = mc.player!!.eyePosition

    // Things to do from where we are, before walking on.
    if (doMines.value) s.mines.firstOrNull { eye.distanceTo(Vec3.atCenterOf(it)) <= REACH }?.let { m ->
        if (mc.player!!.level().getBlockState(m).isAir) {
            s.mines.remove(m); return
        }
        val slot = PlayerUtils.findHotbarSlot { it.skyblockId == "DUNGEONBREAKER" || it.skyblockId.contains("PICKAXE") || it.skyblockId.contains("DRILL") }
        if (slot != null) PlayerUtils.swapToSlot(slot)
        releaseMovement()
        actionTarget = m
        timer = 0
        phase = AutoRoutes.Phase.MINING
        return
    }
    if (doTnt.value) s.tnts.firstOrNull { eye.distanceTo(Vec3.atCenterOf(it)) <= REACH }?.let { t ->
        val slot = PlayerUtils.findHotbarSlot { it.skyblockId.contains("SUPERBOOM") } ?: return run { s.tnts.remove(t) }
        PlayerUtils.swapToSlot(slot)
        if (aimThen(Vec3.atCenterOf(t), use = true)) s.tnts.remove(t)
        return
    }
    if (doLevers.value) s.levers.firstOrNull { eye.distanceTo(Vec3.atCenterOf(it)) <= REACH }?.let { l ->
        if (aimThen(Vec3.atCenterOf(l), use = true)) s.levers.remove(l)
        return
    }
    if (doPearls.value) s.pearls.firstOrNull { horizontal(pos, it.first) < 0.8 && abs(pos.y - it.first.y) < 1.5 }?.let { pearl ->
        val slot = PlayerUtils.findHotbarSlot { it.skyblockId == "ENDER_PEARL" || it.hoverName.string.contains("Ender Pearl") } ?: return run { s.pearls.remove(pearl) }
        PlayerUtils.swapToSlot(slot)
        releaseMovement()
        if (! turn(pearl.second.yaw, pearl.second.pitch, 1f)) return
        s.pearls.remove(pearl)
        if (zeroTick) {
            useNow()
            wait = 10
            return
        }
        phase = AutoRoutes.Phase.PEARL_WAIT
        wait = actionDelay
        return
    }

    if (pathIndex >= s.path.size) {
        releaseMovement()
        phase = AutoRoutes.Phase.SECRET
        return
    }

    // re-path check on a spread interval (Re-path Every / Spread), not every tick
    if (--repathIn <= 0) {
        resync(s, pos)
        repathIn = dev.riftwalk.utils.HumanMotion.jittered(dev.riftwalk.features.impl.general.HumanBehaviour.repathEvery.value, dev.riftwalk.features.impl.general.HumanBehaviour.repathSpread.value / 100.0, moveRnd)
    }
    val target = s.path[pathIndex]
    val ether = if (target in s.ethers) target else target.below().takeIf { it in s.ethers }
    if (ether != null && pos.distanceTo(standOn(ether)) > 1.2) {
        val slot = PlayerUtils.findHotbarSlot { EtherwarpHelper.getEtherwarpDistance(it) != null }
        if (slot != null) {
            PlayerUtils.swapToSlot(slot)
            releaseMovement()
            val alreadySneaking = mc.player!!.isShiftKeyDown
            PlayerUtils.toggleSneak(true)
            val aim = etherAim(ether, mc.player!!.inventory.getItem(slot))
            if (! turn(aim.yaw, aim.pitch, 1f)) return
            etherTarget = ether
            status = "etherwarp to ${ether.x}, ${ether.y}, ${ether.z}"
            if (zeroTick && alreadySneaking) {
                useNow()
                phase = AutoRoutes.Phase.ETHER_WAIT
                timer = 0
            }
            else {
                phase = AutoRoutes.Phase.ETHER_AIM
                wait = actionDelay
            }
            return
        }
    }

    val goal = if (ether != null) standOn(ether) else Vec3(target.x + 0.5, target.y.toDouble(), target.z + 0.5)
    if (arrivedAt(goal, pos, 0.55, key = s to pathIndex) && abs(pos.y - goal.y) < 1.6) {
        pathIndex++
        stuckTicks = 0
        climbTries = 0
        return
    }
    val flat = horizontal(pos, goal)
    if (ether == null && mc.player!!.onGround()) {
        if (climbAotv.value && goal.y - pos.y > 1.3 && (flat < 4.0 || stuckTicks > 10)) {
            if (++climbTries > 80) return stop("can't get up to ${goal.x.toInt()}, ${goal.y.toInt()}, ${goal.z.toInt()}")
            if (leap(s, pos, goal, allowAotv = true)) return
        }
        else if (fastTravel.value && flat > 6.0 && abs(goal.y - pos.y) < 8.0) {
            if (leap(s, pos, goal, allowAotv = flat >= 9.5)) return
        }
    }
    walkTowards(pos, goal)
}

internal fun AutoRoutes.walkTowards(pos: Vec3, goal: Vec3, profile: dev.riftwalk.utils.HumanProfile = dev.riftwalk.utils.Humanize.profile()) {
    val p = mc.player!!
    lastDriveTick = moveTick
    val goalBlock = BlockPos.containing(goal.x, goal.y, goal.z)
    if (useAssistPath.value && AssistBridge.pathLoaded && goalBlock != assistFailedGoal && (AssistBridge.walking || stuckTicks > assistAfter || horizontal(pos, goal) > 14.0)) {
        when (AssistBridge.walkTo(mc, p, goalBlock)) {
            AssistBridge.RUN -> {
                stuckTicks = 0
                lastPos = pos
                status = "assist path to ${goalBlock.x}, ${goalBlock.y}, ${goalBlock.z}"
                return
            }
            AssistBridge.DONE -> {
                stuckTicks = 0
                releaseMovement()
                return
            }
            else -> {
                if (assistLog.value) AssistBridge.logEvent("Assist Path gave up: ${AssistBridge.failReason() ?: "no way"}")
                assistFailedGoal = goalBlock // don't plan to this point again, finish with the straight walk
                stuckTicks = 0
            }
        }
    }
    val hb = dev.riftwalk.features.impl.general.HumanBehaviour
    startWalking(profile)
    val yaw = Math.toDegrees(atan2(goal.z - pos.z, goal.x - pos.x)).toFloat() - 90f
    // the walking pitch wanders (Idle Drift) instead of sitting on one value
    walkPitchOff = dev.riftwalk.utils.HumanMotion.walk(walkPitchOff, hb.idleStep.value.toFloat(), hb.idleDrift.value.toFloat(), moveRnd)
    turn(yaw + startOffset(), if (speed.value == 3) p.xRot else 10f + walkPitchOff, 6f, settle = false)
    mc.options.keyUp.isDown = true
    sprintKey(sprint.value)
    val needJump = autoJump.value && p.onGround() && ((goal.y - pos.y > 0.6 && horizontal(pos, goal) < 2.5) || p.horizontalCollision)
    jumpKey(needJump)

    val moved = lastPos?.distanceTo(pos) ?: 1.0
    lastPos = pos
    stuckTicks = if (moved < 0.02) stuckTicks + 1 else 0
    if (stuckTicks == 1) { // a new stuck spell: its own jump and Assist Path thresholds
        val spread = hb.repathSpread.value / 100.0
        stuckJumpAt = dev.riftwalk.utils.HumanMotion.jittered(12, spread, moveRnd)
        assistAfter = dev.riftwalk.utils.HumanMotion.jittered(8, spread, moveRnd)
    }
    if (stuckTicks in stuckJumpAt .. stuckJumpAt + 18 && p.onGround()) mc.options.keyJump.isDown = true
    if (stuckTicks > 40 && ! running) {
        travelStuck = true
        stuckTicks = 0
        return
    }
    if (stuckTicks > 40) {
        if (! recover.value || ++recoveries > 4) return stop("stuck - can't reach ${goal.x.toInt()}, ${goal.y.toInt()}, ${goal.z.toInt()}")
        stuckTicks = 0
        status = "stuck, recovering (${recoveries}/4)"
        if (assistLog.value) AssistBridge.logEvent("stuck near ${pos.x.toInt()}, ${pos.y.toInt()}, ${pos.z.toInt()}, recovery $recoveries")
        assistFailedGoal = null
        // 1: AOTV towards the goal (or straight up if it's above), 2+: also forget etherwarps here and re-find the route
        val s = step
        if (s != null && leap(s, pos, goal.add(0.0, if (goal.y > pos.y + 1) 0.0 else 1.0, 0.0), allowAotv = true)) return
        if (s != null && recoveries >= 2) resync(s, pos, force = true)
    }
    status = "walk to ${goal.x.toInt()}, ${goal.y.toInt()}, ${goal.z.toInt()}"
}

internal fun AutoRoutes.collectSecret(s: AutoRoutes.Step, pos: Vec3) {
    val timer = ++secretTimer
    val sp = s.secretPos
    if (! doSecret.value || sp == null || s.secretType == null || s.secretType == "exitroute") {
        if (timer > 40) phase = AutoRoutes.Phase.DONE
        return
    }
    when (s.secretType) {
        "interact" -> if (timer % 10 == 1) {
            if (mc.player!!.eyePosition.distanceTo(Vec3.atCenterOf(sp)) > REACH) walkTowards(pos, Vec3.atCenterOf(sp))
            else aimThen(Vec3.atCenterOf(sp), use = true)
        }

        "item" -> if (horizontal(pos, Vec3.atCenterOf(sp)) > 0.4) walkTowards(pos, Vec3(sp.x + 0.5, sp.y.toDouble(), sp.z + 0.5)) else releaseMovement()
        "bat" -> {
            releaseMovement()
            val bat = mc.player!!.level().getEntitiesOfClass(Bat::class.java, mc.player!!.boundingBox.inflate(8.0)).minByOrNull { it.distanceTo(mc.player!!) }
            if (bat != null && timer % 3 == 0) {
                if (! turnTo(bat.boundingBox.center)) return
                if (zeroTick) {
                    sendRotation()
                    mc.gameMode?.attack(mc.player!!, bat)
                    mc.player!!.swing(InteractionHand.MAIN_HAND)
                }
                else {
                    actionUse = false
                    phase = AutoRoutes.Phase.ACTION_AIM
                }
            }
        }
    }
    if (timer > 200) {
        if (skipStuckSecret.value) {
            releaseMovement()
            phase = AutoRoutes.Phase.DONE
            status = "secret unreachable, skipped"
            return stop("skipped an unreachable secret")
        }
        stop("couldn't get the secret")
    }
}

/**
 * Back on track after a fall or a knock-back: if the next point is far but another point further along
 * (or the one before) is close, continue from there.
 */
internal fun AutoRoutes.resync(s: AutoRoutes.Step, pos: Vec3, force: Boolean = false) {
    if (s.path.isEmpty() || pathIndex >= s.path.size) return
    val cur = s.path[pathIndex]
    val curDist = pos.distanceTo(Vec3(cur.x + 0.5, cur.y.toDouble(), cur.z + 0.5))
    if (! force && curDist < 10.0) return
    val from = (pathIndex - 1).coerceAtLeast(0)
    val best = (from until s.path.size).minByOrNull { i ->
        val b = s.path[i]
        pos.distanceTo(Vec3(b.x + 0.5, b.y.toDouble(), b.z + 0.5)) + (i - pathIndex).coerceAtLeast(0) * 0.25
    } ?: return
    val bestDist = s.path[best].let { pos.distanceTo(Vec3(it.x + 0.5, it.y.toDouble(), it.z + 0.5)) }
    if (best != pathIndex && bestDist + 3.0 < curDist) {
        pathIndex = best
        stuckTicks = 0
        status = "back on route at point ${best + 1}"
    }
}

/**
 * Gets up to [goal] when walking can't: etherwarp onto the block under it if the landing is predicted,
 * otherwise an AOTV teleport (no sneak) towards it. Returns true if it started something this tick.
 */
internal fun AutoRoutes.leap(s: AutoRoutes.Step, pos: Vec3, goal: Vec3, allowAotv: Boolean): Boolean {
    val p = mc.player ?: return false
    val under = BlockPos.containing(goal.x, goal.y - 0.5, goal.z)
    val etherSlot = PlayerUtils.findHotbarSlot { EtherwarpHelper.getEtherwarpDistance(it) != null }
    if (etherSlot != null && under !in s.ethers && ! p.level().getBlockState(under).isAir) {
        etherAim(under, p.inventory.getItem(etherSlot))
        if (lastEtherCheck == true) {
            s.ethers += under // the normal etherwarp step takes it from here
            status = "etherwarp to ${under.x}, ${under.y}, ${under.z}"
            return true
        }
    }
    if (! allowAotv) return false
    return aotvLeap(pos, goal)
}

/** AOTV (no sneak) towards [goal]. True if it started (or is turning for) a teleport this tick. */
internal fun AutoRoutes.aotvLeap(pos: Vec3, goal: Vec3): Boolean {
    val p = mc.player ?: return false
    val aotvSlot = PlayerUtils.findHotbarSlot { it.skyblockId in AOTV_IDS } ?: return false
    PlayerUtils.swapToSlot(aotvSlot)
    releaseMovement()
    PlayerUtils.toggleSneak(false)
    if (mc.player!!.isShiftKeyDown) return true // let the un-sneak reach the server first
    val dx = goal.x - pos.x
    val dz = goal.z - pos.z
    val yaw = Math.toDegrees(atan2(dz, dx)).toFloat() - 90f
    val flat = sqrt(dx * dx + dz * dz)
    val pitch = - Math.toDegrees(atan2(goal.y + 0.2 - (pos.y + p.eyeHeight), flat)).toFloat()
    status = "AOTV to ${goal.x.toInt()}, ${goal.y.toInt()}, ${goal.z.toInt()}"
    if (! turn(yaw, pitch.coerceIn(- 90f, 90f), 2f)) return true
    aotvFromY = pos.y
    aotvFrom = pos
    if (zeroTick) useNow() else PlayerUtils.rightClick()
    phase = AutoRoutes.Phase.AOTV_WAIT
    timer = 0
    return true
}

/**
 * Picks where to look so the etherwarp lands on [target]: tries the top face centre and a few points around it,
 * using Noamm's etherwarp prediction (same raycast rules as the server), sneaking eye height.
 */
internal fun AutoRoutes.etherAim(target: BlockPos, stack: ItemStack): MathUtils.Rotation {
    val centre = Vec3(target.x + 0.5, target.y + 0.98, target.z + 0.5)
    val fallback = MathUtils.calcYawPitch(centre)
    if (! checkEther.value) return fallback.also { lastEtherCheck = null }
    val p = mc.player ?: return fallback
    val dist = EtherwarpHelper.getEtherwarpDistance(stack) ?: 57.0
    val offsets = listOf(0.0, 0.25, - 0.25, 0.4, - 0.4)
    for (dx in offsets) for (dz in offsets) {
        val rot = MathUtils.calcYawPitch(centre.add(dx, 0.0, dz))
        val look = Vec3.directionFromRotation(rot.pitch, rot.yaw)
        val landing = EtherwarpHelper.getEtherPos(p.position(), look, dist)
        if (landing.succeeded && landing.pos == target) {
            lastEtherCheck = true
            return rot
        }
    }
    lastEtherCheck = false
    return fallback
}

// ---- human movement (Human Behaviour › Movement). Every view change goes through turn() / Humanize.

/**
 * Arrived at [goal] (within [radius] blocks, flat) with a fresh offset per waypoint ([key]): the offset centre's
 * circle shrinks by the offset, so arrival positions are a subset of the plain check's.
 */
internal fun AutoRoutes.arrivedAt(goal: Vec3, pos: Vec3, radius: Double, key: Any): Boolean {
    val hb = dev.riftwalk.features.impl.general.HumanBehaviour
    if (key != arrivalKey) {
        arrivalKey = key
        val cap = if (hb.enabled) minOf(hb.waypointOffset.value, dev.riftwalk.utils.HumanMotion.offsetCap(radius)) else 0.0
        arrivalOff = dev.riftwalk.utils.HumanMotion.waypointOffset(cap, arrivalOff, moveRnd)
    }
    val o = arrivalOff ?: doubleArrayOf(0.0, 0.0)
    return dev.riftwalk.utils.HumanMotion.arrived(pos.x - goal.x, pos.z - goal.z, o[0], o[1], radius)
}

/** Going from standing to walking: with the profile's slip chance, the first steps head a little off (Start Wobble). */
internal fun AutoRoutes.startWalking(profile: dev.riftwalk.utils.HumanProfile) {
    if (walking) return
    walking = true
    val hb = dev.riftwalk.features.impl.general.HumanBehaviour
    startWobbleLeft = 6
    startYawOff = if (hb.enabled && dev.riftwalk.utils.HumanMotion.chance(profile.slipChance * 100, moveRnd))
        (moveRnd.nextFloat() * 2 - 1) * hb.startWobble.value.toFloat() else 0f
}

/** This tick's share of the start wobble (fades out over 6 ticks). */
internal fun AutoRoutes.startOffset(): Float {
    if (startWobbleLeft <= 0) return 0f
    return startYawOff * (startWobbleLeft-- / 6f)
}

/** Sprint key with a delay after each change of mind (Sprint Delay Min / Max). */
internal fun AutoRoutes.sprintKey(want: Boolean) {
    val hb = dev.riftwalk.features.impl.general.HumanBehaviour
    if (want != sprintWant) {
        sprintWant = want
        sprintWait = if (hb.enabled) dev.riftwalk.utils.HumanMotion.between(hb.sprintDelayMin.value, hb.sprintDelayMax.value, moveRnd) else 0
    }
    if (sprintWait > 0) sprintWait-- else sprintNow = sprintWant
    mc.options.keySprint.isDown = sprintNow
}

/** Jump key: when a jump is needed, waits a log-normal number of ticks first (Jump Delay / Spread / Max), then holds it. */
internal fun AutoRoutes.jumpKey(want: Boolean) {
    val hb = dev.riftwalk.features.impl.general.HumanBehaviour
    if (! want) {
        jumpWait = - 1
        mc.options.keyJump.isDown = false
        return
    }
    if (jumpWait < 0) jumpWait = if (hb.enabled) dev.riftwalk.utils.HumanMotion.lognormalTicks(hb.jumpDelay.value, hb.jumpSpread.value, 0, hb.jumpMax.value, moveRnd) else 0
    if (jumpWait > 0) {
        jumpWait--
        mc.options.keyJump.isDown = false
    }
    else mc.options.keyJump.isDown = true
}

/**
 * End of tick: while a route feature is active but standing still and not aiming, the view wanders in a bounded
 * random walk (Idle Drift) instead of holding one exact direction. Any turn() moves the anchor (offsets reset).
 */
internal fun AutoRoutes.idleLook() {
    val p = mc.player ?: return
    val hb = dev.riftwalk.features.impl.general.HumanBehaviour
    if (! hb.enabled || moveTick - lastDriveTick > 40 || mc.gui.screen() != null) { idleYaw = 0f; idlePitch = 0f; return }
    if (lastTurnTick == moveTick || mc.options.keyUp.isDown) { idleYaw = 0f; idlePitch = 0f; return }
    // never while an aim is being held for a click / etherwarp / pearl / mining
    if (running && phase in setOf(AutoRoutes.Phase.ETHER_AIM, AutoRoutes.Phase.ACTION_AIM, AutoRoutes.Phase.PEARL_WAIT, AutoRoutes.Phase.MINING)) return
    val bound = hb.idleDrift.value.toFloat()
    if (bound <= 0f) return
    val step = hb.idleStep.value.toFloat()
    val ny = dev.riftwalk.utils.HumanMotion.walk(idleYaw, step, bound, moveRnd)
    val np = dev.riftwalk.utils.HumanMotion.walk(idlePitch, step * 0.6f, bound * 0.6f, moveRnd)
    dev.riftwalk.utils.Humanize.glide(p, p.yRot + (ny - idleYaw), (p.xRot + (np - idlePitch)).coerceIn(- 90f, 90f))
    idleYaw = ny; idlePitch = np
}

//#endif
