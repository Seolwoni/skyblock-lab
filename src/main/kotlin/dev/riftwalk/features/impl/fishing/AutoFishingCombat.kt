package dev.riftwalk.features.impl.fishing

//#if CHEAT
import dev.riftwalk.Riftwalk.mc
import dev.riftwalk.config.types.DropdownSetting
import dev.riftwalk.config.types.KeybindSetting
import dev.riftwalk.config.types.SliderSetting
import dev.riftwalk.config.types.ToggleSetting
import dev.riftwalk.event.impl.MainThreadPacketReceivedEvent
import dev.riftwalk.event.impl.TickEvent
import dev.riftwalk.event.impl.WorldChangeEvent
import dev.riftwalk.features.Feature
import dev.riftwalk.features.impl.routes.AssistBridge
import dev.riftwalk.features.impl.routes.AutoRoutes
import dev.riftwalk.ui.notification.NotificationManager
import dev.riftwalk.utils.ChatUtils.modMessage
import dev.riftwalk.utils.ColorUtils.withAlpha
import dev.riftwalk.utils.GuiUtils
import dev.riftwalk.utils.MathUtils
import dev.riftwalk.utils.PlayerUtils
import dev.riftwalk.utils.items.ItemUtils.skyblockId
import dev.riftwalk.utils.render.Render2D.drawString
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
import dev.riftwalk.config.types.TextInputSetting

// Sea creatures: which ones are ours, the Hyperion and whip fights, and the smooth per-frame tracking.

/**
 * Works out which new mobs are ours. Looks around each of our reels (not around the player, so a hook far
 * below or above still counts) and gives a new mob 30 ticks to settle, because the server can spawn it a
 * little off and move it into place.
 */
/**
 * Works out which new mobs are ours, three ways together:
 *  1. Catch message: the server tells only the catcher what they caught ("...Sea Walker..."). A new mob near our
 *     hook whose name is in a message we just got is ours for sure (two on a Double Hook). Once this server has
 *     sent such a message, this is the only way a creature is claimed (other people's never message us).
 *  2. When and where: appeared right after our reel, right where our hook was.
 *  3. Who it goes for: over the next seconds it closes in on us and faces us.
 * Without catch messages, 2 and 3 are scored together.
 */
internal fun AutoFishing.claimOwn(p: LocalPlayer) {
    val lvl = p.level()
    // when every mob around first showed up (so an older creature is never taken for a new one)
    val around = lvl.getEntitiesOfClass(Mob::class.java, p.boundingBox.inflate(40.0, 24.0, 40.0)) { it.isAlive && it.javaClass.simpleName !in NPC_CLASSES }
    for (e in around) if (e.id !in seenAt) { seenAt[e.id] = tickCount; seenPos[e.id] = e.position() }
    if (seenAt.size > 3000) { val live = around.map { it.id }.toSet(); seenAt.keys.retainAll(live); seenPos.keys.retainAll(live); chase.keys.retainAll(live) }

    val window = (ownWindow.value * 20).toInt()
    while (reels.isNotEmpty() && tickCount - reels.first().tick > 80) reels.removeFirst()
    while (catches.isNotEmpty() && tickCount - catches.first().first > 60) catches.removeFirst()
    if (catches.isEmpty() && (reels.isEmpty() || tickCount - reels.last().tick > 40)) claimsLeft = 0
    if (reels.isEmpty()) return claimCompanions(p, around)

    for (e in around) {
        if (e.id in mine) continue
        val first = seenAt[e.id] ?: continue
        if (tickCount - first > 70) continue // only new mobs are looked at, for ~3.5 s
        val at = seenPos[e.id] ?: continue
        val reel = reels.lastOrNull { first >= it.tick && first - it.tick <= 40 } ?: continue // came after one of our reels

        // 1. catch message naming it
        val name = creatureName(e)
        if (useCatchMsgs.value && name != null) {
            val msg = catches.firstOrNull { (t, m) -> t >= reel.tick - 2 && name.lowercase() in m }
            if (msg != null && claimsLeft > 0 && at.distanceTo(reel.at) <= 10.0) {
                catchMsgsWork = true
                claimsLeft--
                claim(e, "catch message")
                continue
            }
        }
        if (useCatchMsgs.value && catchMsgsWork) continue // this server sends them: nothing else counts

        // 2. + 3. scored
        var score = 0.0
        val dt = first - reel.tick
        if (dt <= window) score += 40.0 * (1.0 - dt / (window + 1.0) * 0.5)
        val d = at.distanceTo(reel.at)
        if (d <= ownDistance.value) score += 35.0 * (1.0 - d / (ownDistance.value * 2))
        val toMe = p.position().subtract(e.position())
        val dist = toMe.length()
        val c = chase.getOrPut(e.id) { 0 to dist }
        val yawToMe = Math.toDegrees(kotlin.math.atan2(toMe.z, toMe.x)).toFloat() - 90f
        val facing = abs(wrap(yawToMe - e.yHeadRot)) < 45f
        val closer = dist < c.second - 0.02
        val n = c.first + (if (closer || (facing && dist < 4.0)) 1 else 0)
        chase[e.id] = n to dist
        score += minOf(30.0, n * 1.5) // up to 30 after ~1 s of coming at us
        if (score >= 70.0) claim(e, "score ${score.toInt()}")
    }
    claimCompanions(p, around)
}

/** A creature's health as SkyBlock shows it on the name tag ("1,200/1,500❤"); the entity's own health is often a dummy. */
internal fun AutoFishing.hpOf(e: LivingEntity): Double {
    val tag = tagOf(e)?.replace(Regex("§."), "")
    val m = tag?.let { dev.riftwalk.utils.SkyblockText.MOB_HEALTH.find(it) }
    return m?.let { parseShort(it.groupValues[1]) } ?: e.health.toDouble()
}

internal fun AutoFishing.hpFraction(e: LivingEntity): Double {
    val tag = tagOf(e)?.replace(Regex("§."), "")
    val m = tag?.let { dev.riftwalk.utils.SkyblockText.MOB_HEALTH.find(it) }
    if (m != null) return (parseShort(m.groupValues[1]) / parseShort(m.groupValues[2]).coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
    return (e.health / e.maxHealth.coerceAtLeast(1f)).toDouble()
}

/**
 * Some catches are several creatures at once (a Magma Slug comes with company, Tadgang is a gang), and some
 * split when killed. New mobs that appear right next to one of ours within half a second of it, or right
 * where one of ours just died, are ours too.
 */
internal fun AutoFishing.claimCompanions(p: LocalPlayer, around: List<Mob>) {
    while (deaths.isNotEmpty() && tickCount - deaths.first().first > 30) deaths.removeFirst()
    for (e in around) {
        if (e.id in mine) continue
        if (tagOf(e) == null) continue // no SkyBlock name tag: a plain mob of the island (e.g. a vanilla magma cube), never a catch
        val first = seenAt[e.id] ?: continue
        if (tickCount - first > 40) continue
        val at = seenPos[e.id] ?: continue
        // spawned together with one of ours
        val buddy = mine.firstOrNull { id ->
            val f = seenAt[id] ?: return@firstOrNull false
            val pos = seenPos[id] ?: return@firstOrNull false
            kotlin.math.abs(f - first) <= 10 && pos.distanceTo(at) <= 4.0
        }
        if (buddy != null) { claim(e, "came with #$buddy"); continue }
        // split from one of ours that just died
        val parent = deaths.firstOrNull { (t, pos) -> first >= t - 2 && first - t <= 20 && pos.distanceTo(at) <= 4.0 }
        if (parent != null) claim(e, "split from a kill")
    }
}

internal fun AutoFishing.claim(e: Entity, how: String = "") {
    journal.log("mine  ${creatureName(e) ?: e.name.string}  #${e.id}  $how")
    mine += e.id
    mineAlive += e.id
    firstSeen += e.id
    creaturesCaught++
    claimedAt[e.id] = System.currentTimeMillis()
    val name = creatureName(e) ?: return
    val rare = rareList.value.split('|').map { it.trim() }.filter { it.isNotEmpty() }.firstOrNull { name.contains(it, ignoreCase = true) } ?: return
    rareIds += e.id
    rareLog += rare
    if (rareAlert.value) {
        NotificationManager.push("Rare sea creature!", "&6$rare &fcame up for you")
        dev.riftwalk.utils.ChatUtils.showTitle("§6§l$rare", "§fcame up for you")
        dev.riftwalk.utils.SoundUtils.playSound(net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP, 1f, 1.2f)
    }
}

/** "Sea Walker" from "[Lv45] Sea Walker 1,200/1,200❤" (colour codes and symbols stripped). */
internal fun AutoFishing.creatureName(e: Entity): String? {
    val tag = tagOf(e)?.replace(Regex("§."), "") ?: return null
    val m = dev.riftwalk.utils.SkyblockText.MOB_TAG.find(tag.trim()) ?: return null
    return m.groupValues[1].replace(Regex("[^A-Za-z' -]"), "").trim().takeIf { it.length >= 3 }
}

/** The SkyBlock name tag that floats over a mob ("[Lv45] Sea Walker 1,200/1,200❤"), if there is one. */
internal fun AutoFishing.tagOf(e: Entity): String? {
    val box = e.boundingBox.inflate(0.4, 2.5, 0.4)
    return dev.riftwalk.utils.FloatingText.near(e.level(), box).map { it.second }.firstOrNull { "❤" in it }
}

/** "0❤" on its name tag: SkyBlock keeps a dying creature around for a moment. */
internal fun AutoFishing.deadTag(e: Entity) = tagOf(e)?.let { dev.riftwalk.utils.SkyblockText.MOB_DEAD.containsMatchIn(it.replace(Regex("§."), "")) } == true

internal fun AutoFishing.creatures(p: LocalPlayer): List<LivingEntity> {
    val r = killRange.value
    val scan = maxOf(r, 24.0)
    val all = p.level().getEntitiesOfClass(Mob::class.java, p.boundingBox.inflate(scan, 8.0, scan)) { e ->
        e.isAlive && e.health > 0f && e.javaClass.simpleName !in NPC_CLASSES
    }
    if (firstSeen.size > 2000) { firstSeen.retainAll(all.map { it.id }.toSet()); mine.retainAll(firstSeen) }
    // anything actually hitting us counts too (self-defence)
    if (p.hurtTime > 0) all.filter { it.distanceTo(p) <= 3.5f && p.hasLineOfSight(it) }.forEach { mine += it.id; ignored -= it.id; inReach.remove(it.id) }
    return all.filter { e ->
        e.id !in ignored && e.distanceTo(p) <= r && (! onlyMine.value || e.id in mine) && ! deadTag(e)
    }
}

internal fun AutoFishing.hasHype() = weaponSlot.value !in 1 .. 9 && PlayerUtils.findHotbarSlot { it.skyblockId in WITHER_BLADES } != null &&
    WHIPS[weaponType.value]?.let { id -> PlayerUtils.findHotbarSlot { it.skyblockId == id } } == null // a whip needs no mana

/**
 * Soul Whip / Flaming Flay: no teleport, so nothing can drop us in the lava. Takes the whip out, aims
 * (eased, not dead centre) at the nearest creature in range and right-clicks Flay, a varied beat apart.
 */
internal fun AutoFishing.whipFight(p: LocalPlayer, mobs: List<LivingEntity>, slot: Int) {
    for (m in mobs) {
        if (p.distanceTo(m) <= whipRange.value) waiting.remove(m.id)
        else if ((waiting.merge(m.id, 1, Int::plus) ?: 0) > giveUp.value * 20) { ignored += m.id; waiting.remove(m.id) }
    }
    // stick with the creature we're already on while it's still in range; switch only when it's gone
    val inRange = mobs.filter { p.distanceTo(it) <= whipRange.value && p.hasLineOfSight(it) }
    // hitting us > rare > most hurt > nearest
    val target = inRange.firstOrNull { it == track } ?: inRange.minWithOrNull(
        compareBy<LivingEntity>({ if (p.hurtTime > 0 && it.distanceTo(p) < 4f) 0 else 1 }, { if (it.id in rareIds) 0 else 1 },
            { hpFraction(it) }, { it.distanceToSqr(p) })
    )
    if (target == null) {
        val far = mobs.minByOrNull { it.distanceToSqr(p) }
        track = far // keep a loose eye on it while it comes closer
        // quick kill: follow it properly through the air, whip already out, so it's hit as soon as it's in range
        trackLoose = ! quickKill.value
        if (quickKill.value && far != null && p.distanceTo(far) < 16f && p.inventory.selectedSlot != slot) hold(slot)
        status = if (far != null) "waiting for ${far.name.string}" else "fighting"
        return
    }
    if (! centered(p)) { status = "stepping to the middle of the block"; return }
    if (track != target) { track = target; trackSettle = 0 }
    trackLoose = false
    ownMoveTicks = 20
    status = "whipping ${target.name.string}"
    val err = trackError(p, target)
    // "on target" = within the creature's own size as seen from here (at least 6 degrees)
    val size = Math.toDegrees(kotlin.math.atan2(maxOf(target.bbWidth, target.bbHeight).toDouble(), p.distanceTo(target).toDouble().coerceAtLeast(1.0))).toFloat()
    if (err < maxOf(6f, size * 0.8f)) trackSettle++ else trackSettle = maxOf(0, trackSettle - 1)
    if (hypeBeat > 0) { hypeBeat--; return }
    if (p.inventory.selectedSlot != slot) { // take the whip out once we're more or less facing it
        if (err < 25f) { hold(slot); hypeBeat = delay(2, 7) }
        return
    }
    // swing only once the aim has stayed on it for a moment, then keep tracking between swings
    if (hitCooldown > 0 || trackSettle < 2) return
    if (clickMissed()) return
    use(p)
    p.swing(InteractionHand.MAIN_HAND)
    hitCooldown = clickGap(whipDelay.value)
}

internal fun AutoFishing.trackPoint(e: Entity, pt: Float): Vec3 {
    val pos = e.getPosition(pt)
    return Vec3(pos.x, pos.y + e.bbHeight * 0.55, pos.z).add(trackOff)
}

internal fun AutoFishing.trackError(p: LocalPlayer, e: Entity): Float {
    val rot = MathUtils.calcYawPitch(trackPoint(e, 1f))
    return kotlin.math.hypot(wrap(rot.yaw - p.yRot).toDouble(), (rot.pitch - p.xRot).toDouble()).toFloat()
}

/**
 * Follows [track] smoothly every frame with a critically damped spring and a top speed, so the view glides
 * after the creature and trails it slightly when it moves, instead of stepping 20 times a second.
 */
internal fun AutoFishing.trackFrame() {
    val now = System.nanoTime()
    val dt = ((now - trackLastNs) / 1e9f).coerceIn(0f, 0.05f)
    trackLastNs = now
    val p = mc.player ?: return
    val e = track
    if (running && state == FishingState.TRAVEL && walkSteer && hop == null) { steerFrame(p, dt); return }
    walkSteer = walkSteer && state == FishingState.TRAVEL
    if (! running || e == null || ! e.isAlive || state != FishingState.FIGHT) { trackVelYaw = 0f; trackVelPitch = 0f; return }
    if (dt <= 0f) return
    val ms = System.currentTimeMillis()
    if (ms >= trackOffNext) { // pick a new spot on its body every 0.4-1.2 s
        val w = e.bbWidth * 0.25
        val h = e.bbHeight * 0.2
        trackOffGoal = Vec3((RANDOM.nextDouble() * 2 - 1) * w, (RANDOM.nextDouble() * 2 - 1) * h, (RANDOM.nextDouble() * 2 - 1) * w)
        trackOffNext = ms + 400 + RANDOM.nextInt(800)
        trackMaxSpeed = 220f + RANDOM.nextFloat() * 200f
    }
    trackOff = trackOff.lerp(trackOffGoal, (dt * 3f).toDouble().coerceAtMost(1.0))
    val rot = MathUtils.calcYawPitch(trackPoint(e, mc.deltaTracker.getGameTimeDeltaPartialTick(true)))
    var errY = wrap(rot.yaw - p.yRot)
    var errP = rot.pitch - p.xRot
    if (trackLoose) { // only keep it roughly in view while waiting
        errY = if (abs(errY) > 20f) errY - kotlin.math.sign(errY) * 20f else 0f
        errP = if (abs(errP) > 15f) errP - kotlin.math.sign(errP) * 15f else 0f
    }
    val k = 55f
    val c = 2f * kotlin.math.sqrt(k)
    trackVelYaw += (k * errY - c * trackVelYaw) * dt
    trackVelPitch += (k * errP - c * trackVelPitch) * dt
    val sp = kotlin.math.hypot(trackVelYaw.toDouble(), trackVelPitch.toDouble()).toFloat()
    if (sp > trackMaxSpeed) { trackVelYaw *= trackMaxSpeed / sp; trackVelPitch *= trackMaxSpeed / sp }
    dev.riftwalk.utils.Humanize.nudgeView(p, trackVelYaw * dt, trackVelPitch * dt)
}

internal fun AutoFishing.fight(p: LocalPlayer, mobs: List<LivingEntity>) {
    val whipSlot = WHIPS[weaponType.value]?.let { id -> PlayerUtils.findHotbarSlot { it.skyblockId == id } }
    if (whipSlot != null) return whipFight(p, mobs, whipSlot)
    val hypeSlot = if (weaponSlot.value in 1 .. 9) null else PlayerUtils.findHotbarSlot { it.skyblockId in WITHER_BLADES }
    // no mana: the Hyperion is still a sword, swing it instead of standing there
    val hype = hypeSlot?.takeIf { ! blastBlocked && dev.riftwalk.utils.ManaGuard.canCast(manaCost.value) }
    if (hypeSlot != null && hype == null) {
        hold(hypeSlot)
        val why = if (blastBlocked) "no safe blast here" else "not enough mana (${dev.riftwalk.utils.ManaGuard.mana ?: "?"}/${manaCost.value})"
        if (tickCount - noManaLoggedAt > 100) { // at most every 5 s
            noManaLoggedAt = tickCount
            journal.log("hyperion: $why - ${if (hypeMelee.value) "swinging it" else "waiting"}")
        }
        // no sword swings with the Hyperion: wait for the mana (the rod stays in, Pause Below Mana holds new casts)
        if (! hypeMelee.value) {
            stopWalking()
            status = if (blastBlocked) "no safe blast here, waiting" else "waiting for mana (${dev.riftwalk.utils.ManaGuard.mana ?: "?"}/${manaCost.value})"
            if (blastBlocked && tickCount % 40 == 0) blastBlocked = false // the spot may be fine again (we moved, it moved)
            return
        }
    }
    val reach = if (hype != null) blastRange.value else 3.2
    // a creature that's in reach but never loses health (out of the blast, immune, already dead): leave it
    val inReachLimit = maxOf(giveUp.value * 20 * 2, 200)
    for (m in mobs) if (p.distanceTo(m) <= reach) {
        val now = hpOf(m).toFloat()
        val (t, hp) = inReach[m.id] ?: (0 to now)
        val n = if (now < hp - 0.01f) 0 else t + 1
        inReach[m.id] = n to now
        if (n > inReachLimit && p.hurtTime == 0) { ignored += m.id; inReach.remove(m.id) }
    }
    if (inReach.size > 200) inReach.keys.retainAll(mobs.map { it.id }.toSet())
    for (m in mobs) {
        if (p.distanceTo(m) <= reach) waiting.remove(m.id)
        else if ((waiting.merge(m.id, 1, Int::plus) ?: 0) > giveUp.value * 20) {
            ignored += m.id
            waiting.remove(m.id)
        }
    }
    val close = mobs.filter { p.distanceTo(it) <= reach }
    if (close.isEmpty()) {
        val far = mobs.minByOrNull { it.distanceToSqr(p) } ?: return
        status = "waiting for ${far.name.string}"
        // quick kill: it's on its way - Hyperion out and looking down already, so it goes off the moment it's in reach
        if (quickKill.value && hype != null && p.distanceTo(far) < 14f && (blastSafe(p, p.yRot, 90f) || centered(p))) {
            if (castDownPitch == 0f) castDownPitch = 80f + RANDOM.nextFloat() * 9f
            if (lavaAround(p) || ! blastSafe(p, p.yRot, castDownPitch)) castDownPitch = 90f
            if (turn(p, p.yRot, castDownPitch, 3f)) {
                if (p.inventory.selectedSlot != hype) { hold(hype); hypeBeat = delay(1, 4) }
                hypeStage = 2
            }
            status = "ready for ${far.name.string}"
            return
        }
        // keep an eye on it loosely: only turn when it's well off to the side, and not onto its exact middle
        val rot = MathUtils.calcYawPitch(dev.riftwalk.utils.Humanize.aimPoint(far.boundingBox.center, 0.6))
        if (abs(wrap(rot.yaw - p.yRot)) > 22f || abs(rot.pitch - p.xRot) > 18f) turn(p, rot.yaw, rot.pitch, 9f)
        return
    }
    if (hype != null && ! blastSafe(p, p.yRot, 90f) && ! centered(p)) { status = "stepping to the middle of the block"; return }
    if (hype != null) {
        status = "Hyperion on ${close.size}"
        if (hypeBeat > 0) { hypeBeat--; return }
        when (hypeStage) {
            0 -> { // glance at the creature first (still holding the rod)
                if (quickKill.value) { hypeStage = 1; return } // no glance: straight down
                val m = close.minByOrNull { it.distanceToSqr(p) } ?: return
                val rot = MathUtils.calcYawPitch(dev.riftwalk.utils.Humanize.aimPoint(m.boundingBox.center, 0.5))
                if (abs(wrap(rot.yaw - p.yRot)) > 15f && ! turn(p, rot.yaw, rot.pitch, 10f)) return
                hypeStage = 1
                hypeBeat = delay(3, 9)
            }
            1 -> { // then look down so the blast covers everything around us
                if (castDownPitch == 0f) castDownPitch = 78f + RANDOM.nextFloat() * 11f
                if (lavaAround(p) || ! blastSafe(p, p.yRot, castDownPitch)) castDownPitch = 90f
                if (! turn(p, p.yRot, castDownPitch, 3f)) return
                dev.riftwalk.utils.Humanize.glide(p, p.yRot, castDownPitch) // the last few degrees, so the cast stage takes it as is
                val swapped = p.inventory.selectedSlot != hype
                hold(hype) // the Hyperion comes out once we're looking down
                hypeStage = 2
                hypeBeat = if (swapped) delay(3, 8) else delay(2, 7) // a beat with it in hand before clicking
            }
            else -> { // cast; between casts keep looking down, each cast a little different
                if (hitCooldown > 0) return
                // only ever looking down: a stage left over from before (or a look that wandered up) goes back to "look down"
                if (p.xRot < 75f) { hypeStage = 1; journal.log("hyperion held: looking ${"%.0f".format(p.xRot)} deg, turning down first"); return }
                // next to lava: straight down only, whatever the pitch drifted to
                if (lavaAround(p) && abs(p.xRot - 90f) > 2f) { castDownPitch = 90f; dev.riftwalk.utils.Humanize.glide(p, p.yRot, 90f); return }
                if (! blastSafe(p, p.yRot, p.xRot)) { // this look would teleport us off the edge: straight down instead
                    castDownPitch = 90f
                    if (abs(p.xRot - 90f) > 2f) { dev.riftwalk.utils.Humanize.glide(p, p.yRot, 90f); return }
                    if (! blastSafe(p, p.yRot, 90f)) { // no safe blast from here: fight it with the sword instead
                        blastBlocked = true
                        hypeStage = 0
                        return
                    }
                }
                hold(hype)
                // a creature that took nothing from the last blast is probably still immune (just spawned): wait a moment
                val m0 = close.minByOrNull { it.distanceToSqr(p) }
                if (m0 != null) {
                    val before = castHp[m0.id]
                    val nowHp = hpOf(m0).toFloat()
                    // a fresh creature that took nothing from the first blast is still immune: wait once, then keep blasting
                    val fresh = (seenAt[m0.id]?.let { tickCount - it } ?: 0) < immuneWindow.value
                    if (fresh && before != null && nowHp >= before - 0.01f && hitCooldown == 0 && hypeBeat == 0 && immuneWaited.add(m0.id)) {
                        hypeBeat = delay(immuneWaitMin.value, immuneWaitMax.value)
                        status = "${m0.name.string} took no damage, waiting a moment"
                        castHp[m0.id] = nowHp
                        return
                    }
                    castHp[m0.id] = nowHp
                }
                if (clickMissed()) return
                ownMoveTicks = 20 // Wither Impact moves us: our doing, not the server's
                journal.log("hyperion  pitch=${"%.1f".format(p.xRot)}  at=${"%.2f,%.2f,%.2f".format(p.x, p.y, p.z)}  lavaNear=${lavaAround(p)}  mana=${dev.riftwalk.utils.ManaGuard.mana ?: "?"}")
                hypeFrom = p.position()
                TravelBlink.fire(p)
                hitCooldown = clickGap(hypeDelay.value)
                castDownPitch = if (lavaAround(p)) 90f else (castDownPitch + (RANDOM.nextFloat() * 2 - 1) * 3f).coerceIn(76f, 90f)
                if (abs(castDownPitch - p.xRot) > 1.5f) {
                    // keep casting: drift the view to the new pitch in place instead of restarting the sequence
                    dev.riftwalk.utils.Humanize.glide(p, p.yRot, castDownPitch)
                }
            }
        }
        return
    }
    if (weaponSlot.value in 1 .. 9) hold(weaponSlot.value - 1)
    val target = close.minByOrNull { it.distanceToSqr(p) } ?: return
    status = if (hypeSlot != null && hype == null) "no mana, hitting ${target.name.string}" else "fighting ${target.name.string}"
    val rot = MathUtils.calcYawPitch(dev.riftwalk.utils.Humanize.aimPoint(target.boundingBox.center, 0.25))
    if (! turn(p, rot.yaw, rot.pitch, 4f) || hitCooldown > 0) return
    if (clickMissed()) return
    hitCooldown = clickGap(hitDelay.value)
    mc.gameMode?.attack(p, target)
    p.swing(InteractionHand.MAIN_HAND)
}



/** A Golden Fish is out: tell the player loudly (once a minute at most), and pause if they want to catch it themselves. */
internal fun AutoFishing.onGoldenFish(how: String) {
    if (goldenFish.value == 0 || tickCount - goldenSeenAt < 1200) return
    goldenSeenAt = tickCount
    journal.log("GOLDEN FISH  $how")
    NotificationManager.push("Golden Fish!", "&6A Golden Fish is out &7($how)", 8000L)
    dev.riftwalk.utils.ChatUtils.showTitle("§6§lGOLDEN FISH", "§fit's out - go get it")
    repeat(3) { dev.riftwalk.utils.SoundUtils.playSound(net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP, 1f, 1.5f) }
    if (goldenFish.value == 2) {
        mc.player?.let { pl -> if (pl.fishing != null) rod()?.let { hold(it); use(pl) } }
        stop("paused for the Golden Fish - start again when you've caught it")
    }
}


/** How far our weapon reaches: the Hyperion blast, the whip, or a sword swing. */
internal fun AutoFishing.fightReach(): Double {
    val whip = WHIPS[weaponType.value]?.let { id -> PlayerUtils.findHotbarSlot { it.skyblockId == id } }
    return when {
        whip != null -> whipRange.value
        hasHype() || PlayerUtils.findHotbarSlot { it.skyblockId in WITHER_BLADES } != null -> blastRange.value
        else -> 3.2
    }
}


/** "1.2k" -> 1200.0, "3,400" -> 3400.0 */
internal fun AutoFishing.parseShort(v: String): Double {
    val t = v.replace(",", "")
    val mult = when (t.lastOrNull()?.lowercaseChar()) { 'k' -> 1e3; 'm' -> 1e6; 'b' -> 1e9; else -> 1.0 }
    return (t.trimEnd('k', 'K', 'm', 'M', 'b', 'B').toDoubleOrNull() ?: 0.0) * mult
}

//#endif
