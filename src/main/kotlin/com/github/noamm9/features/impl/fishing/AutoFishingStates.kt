package com.github.noamm9.features.impl.fishing

//#if CHEAT
import com.github.noamm9.config.types.DropdownSetting
import com.github.noamm9.config.types.KeybindSetting
import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.config.types.TextInputSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.MainThreadPacketReceivedEvent
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.features.impl.routes.AssistBridge
import com.github.noamm9.features.impl.routes.AutoRoutes
import com.github.noamm9.ui.notification.NotificationManager
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import com.github.noamm9.utils.ChatUtils.modMessage
import com.github.noamm9.utils.MathUtils
import com.github.noamm9.utils.PlayerUtils
import com.github.noamm9.utils.GuiUtils
import com.github.noamm9.utils.ColorUtils.withAlpha
import com.github.noamm9.utils.items.ItemUtils.skyblockId
import com.github.noamm9.utils.render.Render2D.drawString
import net.minecraft.client.player.LocalPlayer
import net.minecraft.tags.FluidTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.projectile.FishingHook
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import java.util.Random
import kotlin.math.abs



// ------------------------------------------------------------------ long breaks

internal fun AutoFishing.scheduleBreak() {
    val lo = minOf(breakEveryMin.value, breakEveryMax.value)
    val hi = maxOf(breakEveryMin.value, breakEveryMax.value)
    nextBreakAt = System.currentTimeMillis() + timing.uniform(lo * 60, hi * 60) * 1000L
}


/** On a break: stand still, now and then look around a little, then back to fishing. */
internal fun AutoFishing.onBreak(p: LocalPlayer) {
    val left = breakUntil - System.currentTimeMillis()
    status = "on a break (${left / 60000}:${"%02d".format((left / 1000) % 60)})"
    if (left <= 0) {
        scheduleBreak()
        offYaw = 0f; offPitch = 0f; moveLen = 0
        state = FishingState.CAST
        returning = true
        wait = castWait()
        info("&7Break over, back to fishing.")
        return
    }
    if (--glanceIn <= 0) {
        // look somewhere else for a while, then back
        toYaw = (RANDOM.nextFloat() * 2 - 1) * 40f
        toPitch = - 10f + RANDOM.nextFloat() * 25f
        fromYaw = offYaw; fromPitch = offPitch
        moveTick = 0
        moveLen = timing.uniform(14, 30)
        glanceIn = timing.uniform(80, 260)
    }
    if (moveLen > 0) {
        moveTick++
        val t = (moveTick / moveLen.toFloat()).coerceAtMost(1f)
        val ease = t * t * (3 - 2 * t)
        offYaw = fromYaw + (toYaw - fromYaw) * ease
        offPitch = fromPitch + (toPitch - fromPitch) * ease
        com.github.noamm9.utils.Humanize.glide(p, anchorYaw + offYaw + wobYaw, (anchorPitch + offPitch + wobPitch).coerceIn(- 90f, 90f))
        if (t >= 1f) moveLen = 0
    }
}


/** What each state does. Enter and exit run when the state changes (seen at the start of the next tick). */
internal interface FishingStateHandler {
    fun enter() {}
    fun tick(player: LocalPlayer?)
    fun exit() {}
}

internal object FishingStates {
    fun of(s: FishingState): FishingStateHandler = when (s) {
        FishingState.FIND -> FindState
        FishingState.TRAVEL -> TravelState
        FishingState.CAST -> CastState
        FishingState.WAIT_BITE -> WaitBiteState
        FishingState.REEL -> ReelState
        FishingState.FIGHT -> FightState
        FishingState.SELL -> SellState
        FishingState.BREAK -> BreakState
        FishingState.RESUME -> ResumeState
    }
}

/**
 * One tick of Auto Fishing. The checks every state shares run here in their old order (failsafes, lava, being
 * pushed away, sea creatures); the state's own work is its handler's tick, called where its `when` branch was.
 */
internal fun AutoFishing.tick() {
    if (state == FishingState.RESUME) return FishingStates.of(state).tick(null)
    relinkBobber()
    val guiOpen = mc.gui.screen() != null
    if (guiWasOpen && ! guiOpen) guiLockUntil = tickCount + timing.uniform(15, 50) // a person needs a moment after closing a menu
    guiWasOpen = guiOpen
    if (state != lastLoggedState) { journal.log("state ${lastLoggedState ?: "-"} -> $state  ($status)"); lastLoggedState?.let { FishingStates.of(it).exit() }; FishingStates.of(state).enter(); lastLoggedState = state }
    if (tickCount % 40 == 0) tabStats = readTabStats()
    if (tickCount % 20 == 0 && goldenFish.value > 0) mc.player?.let { pl ->
        com.github.noamm9.utils.FloatingText.near(pl.level(), pl.boundingBox.inflate(30.0, 15.0, 30.0))
            .firstOrNull { (_, txt) -> txt.contains(com.github.noamm9.utils.SkyblockText.GOLDEN_FISH, ignoreCase = true) }
            ?.let { onGoldenFish("seen ${pl.distanceTo(it.first).toInt()}m away") }
    }
    val goalHit = when {
        stopAfterMin.value > 0 && System.currentTimeMillis() - sessionStart > stopAfterMin.value * 60_000L -> "${stopAfterMin.value} min of fishing"
        stopAfterKills.value > 0 && kills >= stopAfterKills.value -> "${stopAfterKills.value} kills"
        else -> null
    }
    if (goalHit != null && state != FishingState.FIGHT) {
        mc.player?.let { pl -> if (pl.fishing != null) rod()?.let { hold(it); use(pl) } }
        NotificationManager.push("Auto Fishing", "&fGoal reached: &d$goalHit")
        return stop("done - goal reached ($goalHit)")
    }
    val p = mc.player ?: return stop("no player")
    val hp = p.health / p.maxHealth * 100f
    if (lowHealth.value == 1) { if (stopHealth.value > 0 && hp < stopHealth.value) return stop("health is low") }
    else {
        // like the mana wait: stop casting until health is back; only a really low health still ends the session
        if (criticalHealth.value > 0 && hp < criticalHealth.value) return stop("health critically low (${hp.toInt()}%)")
        if (! healthWaiting && stopHealth.value > 0 && hp < stopHealth.value) { healthWaiting = true; journal.log("health ${hp.toInt()}% - waiting for it to come back") }
        if (healthWaiting && hp >= resumeHealth.value) { healthWaiting = false; journal.log("health back to ${hp.toInt()}% - fishing again") }
    }
    if (hitCooldown > 0) hitCooldown--
    if (ownMoveTicks > 0) ownMoveTicks--
    // a sudden big jump in where we look (more than any turn of ours can do in a tick): stop
    if (failsafe.value && lastLookYaw != null && ownMoveTicks <= 0) {
        val dy = abs(wrap(p.yRot - lastLookYaw!!))
        val dp = abs(p.xRot - lastLookPitch)
        if (kotlin.math.sqrt(dy * dy + dp * dp) >= 70f) return failStop("your view suddenly turned ${kotlin.math.sqrt(dy * dy + dp * dp).toInt()} degrees")
    }
    lastLookYaw = p.yRot
    lastLookPitch = p.xRot
    // a long jump in one tick that isn't ours (no warp / AOTV / Wither Impact just now)
    val here = p.position()
    // dying / respawning or a new world (lobby, other server) isn't a teleport: start counting again
    val sameWorld = lastTickLevel === p.level() && lastTickPlayer === p && ! p.isDeadOrDying
    val before = if (sameWorld) lastTickPos else null
    lastTickPos = here
    lastTickLevel = p.level()
    lastTickPlayer = p
    if (failsafe.value && stopOnTeleport.value && before != null && ownMoveTicks <= 0 && state != FishingState.RESUME &&
        com.github.noamm9.utils.WatchSigns.teleported(before.x, before.y, before.z, here.x, here.y, here.z, teleportDistance.value.toDouble())
    ) return failStop("teleported ${before.distanceTo(here).toInt()} blocks without a warp")
    if (failsafe.value && stopOnStaff.value && tickCount % 20 == 0) staffNearby(p)?.let { return failStop("staff nearby: $it") }
    timing.tick()
    if (failsafe.value) {
        if (expectedSlot >= 0 && p.inventory.selectedSlot != expectedSlot) {
            if (tickCount - serverSlotAt <= 3) { // the server switched it: take that as ours and carry on
                journal.log("the server switched the held slot ${expectedSlot + 1} -> ${p.inventory.selectedSlot + 1}")
                expectedSlot = p.inventory.selectedSlot
            }
            else return failStop("your held item was changed (slot ${expectedSlot + 1} -> ${p.inventory.selectedSlot + 1})")
        }
        val sc = mc.gui.screen()
        if (sc is AbstractContainerScreen<*> && sc !is InventoryScreen && state != FishingState.SELL && ownMoveTicks <= 0) return failStop("a menu opened by itself (${sc.title.string})")
    }

    tickCount++
    if (! p.isInLava && ! p.isInWater && p.onGround()) { lastSafe = p.position(); lavaTicks = 0 }
    hypeFrom?.let { from -> // the tick after a Hyperion cast: write down where it took us
        hypeFrom = null
        val moved = from.distanceTo(p.position())
        if (moved > 0.6 || p.isInLava) journal.log("hyperion moved ${"%.2f".format(moved)}  to=${"%.2f,%.2f,%.2f".format(p.x, p.y, p.z)}${if (p.isInLava) "  IN LAVA" else ""}")
    }
    // stall watchdog: same state, hook, counts and block for too long (and not on a break) - say why, start over
    if (stallSeconds.value > 0) {
        val nums = FishingTracker.numbers()
        val sig = "$state|${p.fishing != null}|${nums["casts"]}|${nums["reels"]}|$kills|${p.blockPosition().toShortString()}"
        if (sig != stallSig || state == FishingState.BREAK) { stallSig = sig; stallSince = tickCount }
        else if (tickCount - stallSince > stallSeconds.value * 20) {
            statStalls++
            val mf = com.github.noamm9.utils.ManaGuard.mana
            journal.log("STALL ${stallSeconds.value}s  state=$state  status=\"$status\"  wait=$wait  timer=$timer  hook=${p.fishing != null}  " +
                "mana=${mf ?: "?"}  hp=${(p.health / p.maxHealth * 100).toInt()}%  healthWaiting=$healthWaiting  hypeStage=$hypeStage  blastBlocked=$blastBlocked  " +
                "path=${path.size}@$pathIndex  hotspot=${hotspot != null}  stand=${stand?.let { BlockPos.containing(it).toShortString() }}  slot=${p.inventory.selectedSlot + 1}/${expectedSlot + 1}")
            info("&eAuto Fishing was stuck (${state.toString().lowercase()}: $status) - starting over.")
            stopWalking()
            if (p.fishing != null) rod()?.let { hold(it); use(p) }
            hypeStage = 0; blastBlocked = false; healthWaiting = false
            path = emptyList(); hotspot = null
            stallSince = tickCount
            state = FishingState.FIND
            return
        }
    }
    if (p.isInLava) return escapeLava(p)

    if (state == FishingState.FIND || state == FishingState.TRAVEL || state == FishingState.SELL) return FishingStates.of(state).tick(p)
    // pushed away from the spot: walk back (not while fighting, sea creatures come to us)
    val home = stand
    if (stayHome.value && home != null && state != FishingState.FIGHT && tickCount - arrivedAt > 60 &&
        (horizontalDist(p.position(), home) > 3.0 || abs(p.y - home.y) > 2.5)) {
        journal.log("pushed ${"%.1f".format(horizontalDist(p.position(), home))} blocks from the spot, going back")
        if (p.fishing != null) rod()?.let { hold(it); use(p) }
        state = FishingState.TRAVEL
        path = emptyList()
        status = "pushed away, going back"
        return
    }

    claimOwn(p)
    // sea creatures first, the moment one comes up
    val seen = if (killCreatures.value && mc.gui.screen() == null) creatures(p) else emptyList()
    if (mineAlive.isNotEmpty()) mineAlive.removeIf { id ->
        val e = p.level().getEntity(id)
        (e == null || ! e.isAlive).also { if (it && e != null) { kills++; deaths += tickCount to e.position() } }
    }
    val nowMs = System.currentTimeMillis()
    for (id in mineAlive) {
        val at = claimedAt[id] ?: continue
        if (nowMs - at > 330_000 && despawnWarned.add(id)) // ~6 min and they despawn
            NotificationManager.push("Sea creature", "&fOne of yours is about to despawn &7(5.5 min old)")
    }
    // a person needs a moment to notice something new: each creature gets its own notice time
    // right after our own reel we know something may come up: a person who's ready reacts faster
    val ready = quickKill.value && reels.isNotEmpty() && tickCount - reels.last().tick < 60
    for (m in seen) noticeAt.getOrPut(m.id) { tickCount + if (ready && m.id in mine) delay(readyMin.value, readyMax.value) else delay(noticeMin.value, noticeMax.value) }
    if (noticeAt.size > 200) noticeAt.keys.retainAll(seen.map { it.id }.toSet())
    val noticed = seen.filter { (noticeAt[it.id] ?: 0) <= tickCount }
    // only stop fishing for what we can actually hit from here; one bobbing up and down below us
    // (in and out of reach) mustn't make us reel in, take the Hyperion out and put it away over and over
    val reach = fightReach()
    val inReach = noticed.filter { p.distanceTo(it) <= reach }
    // our own just-caught creature counts even below us (fishing from up high it drops away otherwise)
    val coming = noticed.filter { p.distanceTo(it) <= reach * 1.6 && (it.y >= p.y - 2.0 || (it.id in mine && p.distanceTo(it) <= reach * 1.25)) }
    val hurt = p.hurtTime > 0
    val mobs = when {
        state == FishingState.FIGHT -> if (inReach.isNotEmpty() || coming.isNotEmpty() || hurt) noticed.filter { p.distanceTo(it) <= reach * 1.6 } else emptyList()
        tickCount - fightLeftAt < 60 && ! hurt -> inReach // just left a fight: only something really in reach brings us back
        quickKill.value -> coming.ifEmpty { inReach }
        else -> inReach
    }
    // leave a fight only after 1.5 s with nothing to hit, so it doesn't flap
    // leave a fight quickly once everything of ours is dead; 1.5 s while one is alive but out of reach (no flapping)
    val leaveAfter = if (mineAlive.isEmpty()) leaveFightAfter.value else 30
    // up high with the Hyperion: ours that sit further below than its reach can't be hit - say so once
    if (! heightWarned && weaponType.value == 0 && noticed.any { it.id in mine && p.y - it.y > reach }) {
        heightWarned = true
        journal.log("a creature of ours is ${"%.0f".format(p.y - noticed.first { it.id in mine }.y)} blocks below, past the Hyperion's reach")
        info("&eYour sea creature is below the Hyperion's reach - fish closer to the lava (2-4 blocks above it) or use a whip.")
    }
    if (state == FishingState.FIGHT && mobs.isEmpty() && ++noReachTicks < leaveAfter) { status = "watching ${noticed.firstOrNull()?.name?.string ?: "around"}"; return }
    if (mobs.isNotEmpty()) noReachTicks = 0
    if (state == FishingState.FIGHT || mobs.isNotEmpty()) {
        fightMobs = mobs
        return FishingStates.of(FishingState.FIGHT).tick(p)
    }

    if (state == FishingState.BREAK) return FishingStates.of(state).tick(p)

    // back to the fishing spot (eased) after a fight
    if (returning) {
        status = "looking back at the ${fluidName()}"
        if (! turn(p, anchorYaw + offYaw + wobYaw, anchorPitch + offPitch + wobPitch, 0.8f)) return
        com.github.noamm9.utils.Humanize.setView(p, anchorYaw + offYaw + wobYaw, anchorPitch + offPitch + wobPitch) // land exactly
        returning = false
    }
    if (state == FishingState.WAIT_BITE) lookMovement(p)

    if (wait > 0) {
        wait--
        return
    }
    if (state == FishingState.CAST || state == FishingState.WAIT_BITE || state == FishingState.REEL) FishingStates.of(state).tick(p)
}

/** FIND: look for a hotspot (or the nearest water / lava). */
internal object FindState : FishingStateHandler {
    override fun tick(player: LocalPlayer?) = with(AutoFishing) { findHotspot(player!!) }
}

/** TRAVEL: walk / hop / teleport to the spot. */
internal object TravelState : FishingStateHandler {
    override fun exit() = TravelBlink.abort()
    override fun tick(player: LocalPlayer?) = with(AutoFishing) { travel(player!!) }
}

/** CAST: step to the block centre, maybe sell or take a break, aim and cast. */
internal object CastState : FishingStateHandler {
    override fun tick(player: LocalPlayer?) {
        with(AutoFishing) {
            val p = player!!
            val rod = rod() ?: return stop("no fishing rod in the hotbar")
            val hook = p.fishing
            if (moveLen > 0) return // don't cast in the middle of a head movement
            if (! centered(p)) { status = "stepping to the middle of the block"; return }
            val free = freeSlots(p)
            if (autoSell.value && free <= sellWhenFree.value && sellFails < 3 && sellables(p).isNotEmpty()) {
                state = FishingState.SELL
                sellStep = 0
                sellTimer = 0
                sellGap = delay(4, 12)
                status = "inventory nearly full, selling"
                return
            }
            if (free == 0) return stop("the inventory is full")
            if (longBreaks.value && nextBreakAt > 0 && System.currentTimeMillis() >= nextBreakAt) {
                if (hook != null) { hold(rod); use(p) }
                val len = timing.uniform(minOf(breakLenMin.value, breakLenMax.value), maxOf(breakLenMin.value, breakLenMax.value))
                breakUntil = System.currentTimeMillis() + len * 1000L
                breakMs += len * 1000L
                glanceIn = timing.uniform(60, 200)
                state = FishingState.BREAK
                info("&7Taking a break for ${len / 60}m ${len % 60}s.")
                return
            }
            val mf = com.github.noamm9.utils.ManaGuard.fraction
            if (healthWaiting) {
                if (p.inventory.selectedSlot != rod) hold(rod)
                status = "waiting for health (${(p.health / p.maxHealth * 100).toInt()}%)"
                return
            }
            if (killCreatures.value && pauseMana.value > 0 && mf != null && mf * 100 < pauseMana.value && hasHype() && manaWait < 600) {
                manaWait++ // at most 30 s, then fish anyway
                if (p.inventory.selectedSlot != rod) hold(rod) // wait with the rod, not standing there with the Hyperion
                status = "waiting for mana (${(mf * 100).toInt()}%)"
                return
            }
            manaWait = 0
            if (creatureCap.value > 0 && mineAlive.size >= creatureCap.value) {
                status = "at the sea creature cap (${mineAlive.size}/${creatureCap.value})"
                return
            }
            // settle on this cast's aim (with its wobble) first, then land on it exactly
            val ty = anchorYaw + offYaw + wobYaw
            val tp = (anchorPitch + offPitch + wobPitch).coerceIn(- 90f, 90f)
            if (! turn(p, ty, tp, 0.35f)) return
            com.github.noamm9.utils.Humanize.setView(p, ty, tp)
            // would this throw come down in the water / lava? if it hits the floor or a wall, aim again first
            if (hook == null && ! throwLandsInFluid(p, ty, tp)) {
                if (++aimFixes > 3) {
                    aimFixes = 0
                    journal.log("the aim keeps hitting the ground - looking for water again")
                    fishTarget = null
                    return goToFluid(p)
                }
                val target = hotspot?.position() ?: fishTarget ?: lookingAtFluid(p) ?: nearestFluid(p, 12)
                if (target == null) { journal.log("cast would land on the ground, no water near - finding some"); return goToFluid(p) }
                journal.log("cast would land on the ground - re-aiming at ${BlockPos.containing(target).toShortString()}")
                fishTarget = fishTarget ?: target
                aimAtPoint(p, target)
                status = "re-aiming at the ${fluidName()}"
                return
            }
            // lands in the fluid but right against a wall / the shore: the hook catches on it - aim at open fluid instead
            if (hook == null) throwLanding(p, ty, tp)?.let { land ->
                if (! wallClear(land)) {
                    if (++aimFixes > 3) { aimFixes = 0; journal.log("no open spot away from the walls - casting anyway") }
                    else {
                        val open = openSpotNear(land, 5)
                        if (open != null) {
                            journal.log("cast would land against a wall at ${BlockPos.containing(land).toShortString()} - re-aiming at ${BlockPos.containing(open).toShortString()}")
                            fishTarget = open
                            aimAtPoint(p, open)
                            status = "aiming away from the wall"
                            return
                        }
                    }
                }
            }
            aimFixes = 0
            hold(rod)
            if (hook != null) { // a line is still out: pull it in first
                use(p)
                wait = castWait()
                return
            }
            use(p)
            nextCastAim(p)
            state = FishingState.WAIT_BITE
            timer = 0
            inFluidTicks = 0
            groundTicks = 0
            lastHookPos = null
            status = "cast"
        }
    }
}

/** WAIT_BITE: watch the hook until a bite (or recast when it went wrong). */
internal object WaitBiteState : FishingStateHandler {
    override fun tick(player: LocalPlayer?) {
        with(AutoFishing) {
            val p = player!!
            val rod = rod() ?: return stop("no fishing rod in the hotbar")
            val hook = p.fishing
            if (healthWaiting) { // low on health: reel in and wait (CAST holds until it's back)
                if (hook != null) { hold(rod); use(p) }
                state = FishingState.CAST
                wait = castWait()
                return
            }
            if (hook == null) {
                if (++timer > 20) { // hook never came / line broke
                    state = FishingState.CAST
                    wait = castWait()
                }
                return
            }
            timer++
            if (timer == 1) { settledAt = null; dustAt.clear() }
            if (inFluid(hook)) inFluidTicks++
            // settled: remember where; then a slide of 2.5+ blocks means it caught on a mob
            if (inFluidTicks == 20) settledAt = hook.position()
            val s0 = settledAt
            if (driftRecast.value && s0 != null && hook.hookedIn == null && ! bitten(hook) &&
                kotlin.math.hypot(hook.x - s0.x, hook.z - s0.z) > 2.5) {
                statDrift++
                journal.log("hook drifted ${"%.1f".format(kotlin.math.hypot(hook.x - s0.x, hook.z - s0.z))} blocks, recasting")
                hold(rod); use(p)
                state = FishingState.CAST
                wait = castWait()
                status = "hook drifted off, recasting"
                return
            }
            // a burst of dust at the hook = it didn't go in (after the cast's own splash, not too often)
            while (dustAt.isNotEmpty() && tickCount - dustAt.first() > 20) dustAt.removeFirst()
            if (particleRecast.value && timer > 30 && dustAt.size >= 8 && tickCount - lastParticleRecast > 60 && ! waitingMarker(hook)) {
                lastParticleRecast = tickCount
                journal.log("splash particles at the hook (${dustAt.size}), recasting")
                statSplash++
                dustAt.clear()
                hold(rod); use(p)
                state = FishingState.CAST
                wait = castWait()
                status = "hook not in the ${fluidName()}, recasting"
                return
            }
            if (inFluidTicks == 8) lastLanding = hook.position()
            status = "waiting ${timer / 20}s in ${fluidName()}" + (if (hotspot != null) " (hotspot)" else "") + (if (mineAlive.isNotEmpty()) " - ${mineAlive.size} alive" else "")
            if (hotspots.value) {
                val hs = hotspot
                val moved = hs != null && hotspotAt != null && hs.position().distanceTo(hotspotAt!!) > 3.0
                val gone = hs != null && (! hs.isAlive || hs.isRemoved || p.level().getEntity(hs.id) !== hs)
                // every 5 s: is there a closer / new hotspot in sight while ours isn't (e.g. it moved as a new entity)
                val elsewhere = hs != null && timer % 100 == 0 && nearestHotspot(p)?.let { it !== hs && it.position().distanceTo(hs.position()) > 3.0 && hs.distanceTo(p) > 20f } == true
                if (moved || gone || elsewhere) { // the hotspot ended or moved: go after it
                    if (hs != null) badHotspots -= hs.id
                    info(if (moved) "&7The hotspot moved, following it." else "&7Hotspot gone, looking for the next one.")
                    hold(rod); use(p)
                    hotspot = null
                    state = FishingState.FIND
                    status = "hotspot gone"
                    return
                }
                if (hs != null && inFluidTicks == 8 && ! landedIn(p, hook, hs)) {
                    hold(rod); use(p)
                    state = FishingState.CAST
                    wait = castWait()
                    return
                }
                if (hs == null && timer % 200 == 0 && nearestHotspot(p) != null) { // one appeared
                    hold(rod); use(p)
                    state = FishingState.FIND
                    return
                }
            }
            if (bitten(hook)) {
                if (tickCount < guiLockUntil) return // just closed a menu: not yet
                if (slugfish.value && timer < (if (slugPet.value) 220 else 420)) return // Slugfish bite only after 20 s
                biteAt = hook.position()
                journal.log("bite after ${timer / 20.0}s  (${fluidName()})")
                state = FishingState.REEL
                wait = reelDelay()
                if (com.github.noamm9.utils.HumanMotion.chance(lateReel.value, RANDOM)) {
                    val late = com.github.noamm9.utils.HumanMotion.between(lateReelTicks.value / 2, lateReelTicks.value, RANDOM)
                    wait += late
                    journal.log("late reel +$late ticks")
                }
                status = "bite!"
                groundFails = 0
                return
            }
            if (groundRecast.value && grounded(hook)) {
                hold(rod)
                use(p)
                state = FishingState.CAST
                wait = castWait()
                statGround++
                if (++groundFails >= 3) return stop("the hook keeps landing on the ground - look at the ${fluidName()}")
                status = "hook on the ground, recasting ($groundFails/3)"
                return
            }
            if (timer > castTimeout.value * 20) {
                hold(rod)
                use(p)
                state = FishingState.CAST
                wait = castWait()
                status = "nothing bit, recasting"
            }
        }
    }
}

/** REEL: reel in after the reel delay. */
internal object ReelState : FishingStateHandler {
    override fun tick(player: LocalPlayer?) {
        with(AutoFishing) {
            val p = player!!
            val rod = rod() ?: return stop("no fishing rod in the hotbar")
            val hook = p.fishing
            hold(rod)
            // where the creature will come up: the hook now, or where it was at the bite if it's gone already
            val at = hook?.position() ?: biteAt
            if (at != null) {
                reels += AutoFishing.Reel(tickCount, at)
                if (reels.size > 6) reels.removeFirst()
            }
            if (hook != null) use(p)
            biteAt = null
            state = FishingState.CAST
            wait = castWait()
            status = "reeled in"
        }
    }
}

/** FIGHT: kill the sea creatures picked by the shared creature check ([AutoFishing.fightMobs]). */
internal object FightState : FishingStateHandler {
    override fun enter() { with(AutoFishing) { if (hypeStage == 2) hypeStage = 1 } } // a new fight starts by looking down again
    override fun tick(player: LocalPlayer?) {
        with(AutoFishing) {
            val p = player!!
            val mobs = fightMobs
            if (mobs.isEmpty()) {
                fightLeftAt = tickCount
                noReachTicks = 0
                track = null
                blastBlocked = false
                hypeStage = 0
                hypeBeat = 0
                castDownPitch = 0f
                state = FishingState.CAST
                returning = true
                if (recastAfterFight.value) justCaughtSomething = false // no extra "reading the catch" pause after a fight
                wait = castWait()
                status = "back to fishing"
                return
            }
            if (state != FishingState.FIGHT) {
                if (p.fishing != null) rod()?.let { hold(it); use(p) } // pull the line in first
                fightWait = if (quickKill.value) delay(1, 4) else delay(3, 10) // then a beat before turning round
            }
            justCaughtSomething = true
            if (state != FishingState.FIGHT) fightTicks = 0
            // never stand there forever: after 40 s of one fight, leave whatever is left and fish again
            if (++fightTicks > 800) {
                mobs.forEach { ignored += it.id }
                fightTicks = 0
                info("&7Left a fight that went on for 40 s.")
                return
            }
            state = FishingState.FIGHT
            if (fightWait > 0) { fightWait--; status = "noticed a sea creature"; return }
            fight(p, mobs)
            return
        }
    }
}

/** SELL: sell through the Booster Cookie menu. */
internal object SellState : FishingStateHandler {
    override fun tick(player: LocalPlayer?) = with(AutoFishing) { sell(player!!) }
}

/** BREAK: a long break, looking around now and then. */
internal object BreakState : FishingStateHandler {
    override fun tick(player: LocalPlayer?) = with(AutoFishing) { onBreak(player!!) }
}

/** RESUME: back to the island after a server reboot. */
internal object ResumeState : FishingStateHandler {
    override fun tick(player: LocalPlayer?) = with(AutoFishing) { resumeTick() }
}

//#endif
