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

// Sea creatures: which ones are ours, the Hyperion and whip fights, and the smooth per-frame tracking.

/**
 * Works out which new mobs are ours. Looks around each of our reels (not around the player, so a hook far
 * below or above still counts) and gives a new mob 30 ticks to settle, because the server can spawn it a
 * little off and move it into place.
 */
internal fun AutoFishing.claimOwn(p: LocalPlayer) {
    val lvl = p.level()
    // remember when every mob around us first showed up, all the time - so a creature someone else
    // fished up earlier is never mistaken for a new one just because we reeled in next to it
    val around = lvl.getEntitiesOfClass(Mob::class.java, p.boundingBox.inflate(40.0, 24.0, 40.0)) { it.isAlive && it.javaClass.simpleName !in NPC_CLASSES }
    for (e in around) if (e.id !in seenAt) { seenAt[e.id] = tickCount; seenPos[e.id] = e.position() }
    if (seenAt.size > 3000) { val live = around.map { it.id }.toSet(); seenAt.keys.retainAll(live); seenPos.keys.retainAll(live) }

    val window = (ownWindow.value * 20).toInt()
    while (reels.isNotEmpty() && tickCount - reels.first().tick > window + 5) reels.removeFirst()
    for (e in around) {
        if (e.id in mine) continue
        val first = seenAt[e.id] ?: continue
        val at = seenPos[e.id] ?: continue
        // ours only if it appeared AFTER one of our reels, within the window, right where our hook was
        if (reels.any { first >= it.tick && first - it.tick <= window && at.distanceTo(it.at) <= ownDistance.value }) {
            mine += e.id
            mineAlive += e.id
            firstSeen += e.id
            creaturesCaught++
        }
    }
}

/** The SkyBlock name tag that floats over a mob ("[Lv45] Sea Walker 1,200/1,200❤"), if there is one. */
internal fun AutoFishing.tagOf(e: Entity): String? {
    val box = e.boundingBox.inflate(0.4, 2.5, 0.4)
    return e.level().getEntitiesOfClass(ArmorStand::class.java, box)
        .mapNotNull { it.customName?.string }
        .firstOrNull { "❤" in it }
}

/** "0❤" on its name tag: SkyBlock keeps a dying creature around for a moment. */
internal fun AutoFishing.deadTag(e: Entity) = tagOf(e)?.let { Regex("(?:^|\\s)0/[\\d,.]+[kKmMbB]?❤").containsMatchIn(it.replace(Regex("§."), "")) || it.replace(Regex("§."), "").contains(" 0❤") } == true

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
    val target = inRange.firstOrNull { it == track } ?: inRange.minByOrNull { it.distanceToSqr(p) }
    if (target == null) {
        val far = mobs.minByOrNull { it.distanceToSqr(p) }
        track = far // keep a loose eye on it while it comes closer
        trackLoose = true
        status = if (far != null) "waiting for ${far.name.string}" else "fighting"
        return
    }
    if (! centered(p)) { status = "stepping to the middle of the block"; return }
    if (track != target) { track = target; trackSettle = 0 }
    trackLoose = false
    ownMoveTicks = 20
    status = "whipping ${target.name.string}"
    val err = trackError(p, target)
    if (err < 3.5f) trackSettle++ else trackSettle = 0
    if (hypeBeat > 0) { hypeBeat--; return }
    if (p.inventory.selectedSlot != slot) { // take the whip out once we're more or less facing it
        if (err < 25f) { hold(slot); hypeBeat = delay(2, 7) }
        return
    }
    // swing only once the aim has stayed on it for a moment, then keep tracking between swings
    if (hitCooldown > 0 || trackSettle < 3) return
    use(p)
    p.swing(InteractionHand.MAIN_HAND)
    hitCooldown = delay(whipDelay.value, whipDelay.value * 2 + 2)
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
    if (running && state == State.TRAVEL && walkSteer && hop == null) { steerFrame(p, dt); return }
    walkSteer = walkSteer && state == State.TRAVEL
    if (! running || e == null || ! e.isAlive || state != State.FIGHT) { trackVelYaw = 0f; trackVelPitch = 0f; return }
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
    p.yRot += trackVelYaw * dt
    p.xRot = (p.xRot + trackVelPitch * dt).coerceIn(- 90f, 90f)
    p.yHeadRot = p.yRot
}

internal fun AutoFishing.fight(p: LocalPlayer, mobs: List<LivingEntity>) {
    val whipSlot = WHIPS[weaponType.value]?.let { id -> PlayerUtils.findHotbarSlot { it.skyblockId == id } }
    if (whipSlot != null) return whipFight(p, mobs, whipSlot)
    val hypeSlot = if (weaponSlot.value in 1 .. 9) null else PlayerUtils.findHotbarSlot { it.skyblockId in WITHER_BLADES }
    // no mana: the Hyperion is still a sword, swing it instead of standing there
    val hype = hypeSlot?.takeIf { ! blastBlocked && com.github.noamm9.utils.ManaGuard.canCast(manaCost.value) }
    if (hypeSlot != null && hype == null) hold(hypeSlot)
    val reach = if (hype != null) blastRange.value else 3.2
    // a creature that's in reach but never loses health (out of the blast, immune, already dead): leave it
    val inReachLimit = maxOf(giveUp.value * 20 * 2, 200)
    for (m in mobs) if (p.distanceTo(m) <= reach) {
        val (t, hp) = inReach[m.id] ?: (0 to m.health)
        val n = if (m.health < hp - 0.01f) 0 else t + 1
        inReach[m.id] = n to m.health
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
        // keep an eye on it loosely: only turn when it's well off to the side, and not onto its exact middle
        val rot = MathUtils.calcYawPitch(com.github.noamm9.utils.Humanize.aimPoint(far.boundingBox.center, 0.6))
        if (abs(wrap(rot.yaw - p.yRot)) > 22f || abs(rot.pitch - p.xRot) > 18f) turn(p, rot.yaw, rot.pitch, 9f)
        return
    }
    if (! centered(p)) { status = "stepping to the middle of the block"; return }
    if (hype != null) {
        status = "Hyperion on ${close.size}"
        if (hypeBeat > 0) { hypeBeat--; return }
        when (hypeStage) {
            0 -> { // glance at the creature first (still holding the rod)
                val m = close.minByOrNull { it.distanceToSqr(p) } ?: return
                val rot = MathUtils.calcYawPitch(com.github.noamm9.utils.Humanize.aimPoint(m.boundingBox.center, 0.5))
                if (abs(wrap(rot.yaw - p.yRot)) > 15f && ! turn(p, rot.yaw, rot.pitch, 10f)) return
                hypeStage = 1
                hypeBeat = delay(3, 9)
            }
            1 -> { // then look down so the blast covers everything around us
                if (castDownPitch == 0f) castDownPitch = 78f + RANDOM.nextFloat() * 11f
                if (! blastSafe(p, p.yRot, castDownPitch)) castDownPitch = 90f
                if (! turn(p, p.yRot, castDownPitch, 3f)) return
                val swapped = p.inventory.selectedSlot != hype
                hold(hype) // the Hyperion comes out once we're looking down
                hypeStage = 2
                hypeBeat = if (swapped) delay(3, 8) else delay(2, 7) // a beat with it in hand before clicking
            }
            else -> { // cast; between casts keep looking down, each cast a little different
                if (hitCooldown > 0) return
                if (! blastSafe(p, p.yRot, p.xRot)) { // this look would teleport us off the edge: straight down instead
                    castDownPitch = 90f
                    if (abs(p.xRot - 90f) > 0.5f) { hypeStage = 1; return }
                    if (! blastSafe(p, p.yRot, 90f)) { // no safe blast from here: fight it with the sword instead
                        blastBlocked = true
                        hypeStage = 0
                        return
                    }
                }
                hold(hype)
                ownMoveTicks = 20 // Wither Impact moves us: our doing, not the server's
                use(p)
                hitCooldown = delay(hitDelay.value, hitDelay.value * 2 + 4)
                castDownPitch = (castDownPitch + (RANDOM.nextFloat() * 2 - 1) * 3f).coerceIn(76f, 90f)
                if (abs(castDownPitch - p.xRot) > 1.5f) { hypeStage = 1; hypeBeat = delay(1, 4) }
            }
        }
        return
    }
    if (weaponSlot.value in 1 .. 9) hold(weaponSlot.value - 1)
    val target = close.minByOrNull { it.distanceToSqr(p) } ?: return
    status = if (hypeSlot != null && hype == null) "no mana, hitting ${target.name.string}" else "fighting ${target.name.string}"
    val rot = MathUtils.calcYawPitch(com.github.noamm9.utils.Humanize.aimPoint(target.boundingBox.center, 0.25))
    if (! turn(p, rot.yaw, rot.pitch, 4f) || hitCooldown > 0) return
    hitCooldown = hitDelay.value
    mc.gameMode?.attack(p, target)
    p.swing(InteractionHand.MAIN_HAND)
}

//#endif
