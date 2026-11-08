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

// Fighting in a room: the Hyperion, picking targets, shooters, attackers, blood camp.

// ------------------------------------------------------------------ combat

/** Kills the nearest star mob / miniboss in detour range. Returns true while busy fighting. */
internal fun AutoRoutes.fight(): Boolean {
    val p = mc.player ?: return false
    if (retaliating) retaliateTicks++ else retaliateTicks = 0
    val keepRetaliating = retaliating && retaliateTicks < 100 && fightTarget?.let { it.isAlive && it.distanceTo(p) < 4f } == true
    var attacker = if (fightBack.value && (p.hurtTime > 0 || keepRetaliating)) findAttacker(p) else null
    // hit from range (arrows, spells): go for the shooter only when Hyperion can reach it, never stand there
    if (attacker == null && fightBack.value && p.hurtTime > 0) attacker = findShooter(p)
    retaliating = attacker != null
    if (clearValue == 0 && attacker == null) return false
    val target = attacker ?: if (clearValue == 2) findRoomTarget(p) else findTarget(p)
    if (target != null && target != fightTarget) hitCooldown = maxOf(hitCooldown, dev.riftwalk.utils.Humanize.reaction())
    fightTarget = target
    if (target == null) return false

    // watchdog: a fight that gets nowhere (no damage dealt, we don't move) for 3 seconds is dropped for 10 s
    val hp = (target as? LivingEntity)?.health ?: 0f
    val here = p.position()
    if (target.id != stallId || hp < stallHp - 0.01f || (stallPos?.distanceTo(here) ?: 0.0) > 1.5) {
        stallId = target.id
        stallHp = hp
        stallPos = here
        stallTicks = 0
    }
    if (++stallTicks > 60) {
        ignored[target.id] = 200
        stallTicks = 0
        retaliating = false
        if (assistLog.value) AssistBridge.logEvent("gave up on ${target.name.string}: no progress for 3 s")
        return false
    }

    val hypeSlot = if (useHype.value) PlayerUtils.findHotbarSlot { it.skyblockId in WITHER_BLADES } else null
    if (hypeSlot != null && dev.riftwalk.utils.ManaGuard.canCast(manaCost.value)) return hypeFight(p, target, hypeSlot)
    if (hypeSlot != null && weaponSlot.value !in 1 .. 9) PlayerUtils.swapToSlot(hypeSlot) // no mana: swing it

    val dist = p.eyePosition.distanceTo(target.boundingBox.clipNearest(p.eyePosition))
    if (dist > attackRange.value) {
        walkTowards(p.position(), target.position())
        status = "chasing ${target.name.string}"
        return true
    }

    releaseMovement()
    if (weaponSlot.value in 1 .. 9) PlayerUtils.swapToSlot(weaponSlot.value - 1)
    status = "fighting ${target.name.string}"
    if (! turnTo(target.boundingBox.center, 4f)) return true
    if (hitCooldown > 0) return true
    hitCooldown = dev.riftwalk.utils.Humanize.around(hitDelay.value)
    if (zeroTick) {
        sendRotation()
        mc.gameMode?.attack(p, target)
        p.swing(InteractionHand.MAIN_HAND)
    }
    else PlayerUtils.leftClick() // rotation from last tick is already on the server
    return true
}

/** Wither Impact: teleport into the pack (if far), then cast looking straight down so the blast covers us. */
internal fun AutoRoutes.hypeFight(p: Player, target: Entity, slot: Int): Boolean {
    PlayerUtils.swapToSlot(slot)
    // a mob that has not lost health after many casts is out of reach (behind glass, in a wall...): leave it for a while
    if (target.id != targetId) {
        targetId = target.id
        castsOnTarget = 0
        targetHealth = (target as? LivingEntity)?.health ?: - 1f
    }
    val hp = (target as? LivingEntity)?.health ?: - 1f
    if (hp < targetHealth - 0.01f) {
        castsOnTarget = 0
        targetHealth = hp
    }
    if (castsOnTarget >= 6) {
        ignored[target.id] = 200
        castsOnTarget = 0
        status = "can't hurt ${target.name.string}, skipping it"
        return false
    }
    val centre = packCentre(p, target)
    val dist = p.position().distanceTo(centre)
    val inBlast = dist <= blastRadius.value - 1.0
    val canTp = hypeTeleport.value && dist <= 11.0 && clearSight(p, centre)
    if (! inBlast && ! canTp) {
        walkTowards(p.position(), centre)
        status = "walking to ${target.name.string}"
        return true
    }
    releaseMovement()
    if (pendingCast) { // rotation went out last tick, now cast
        PlayerUtils.rightClick()
        pendingCast = false
        return true
    }
    if (hitCooldown > 0) return true
    hitCooldown = dev.riftwalk.utils.Humanize.around(castDelay.value)
    castsOnTarget++
    val aimed = if (inBlast) {
        status = "casting on ${target.name.string}"
        turn(p.yRot, 90f, 3f)
    }
    else {
        status = "teleporting to ${target.name.string}"
        turnTo(centre.add(0.0, 0.2, 0.0), 4f)
    }
    if (! aimed) {
        hitCooldown = 0 // cast as soon as the aim arrives
        return true
    }
    if (zeroTick) useNow() else pendingCast = true
    return true
}

/** Middle of the mobs within blast range of [target] (moved ahead along their movement with Lead Moving Mobs). */
internal fun AutoRoutes.packCentre(p: Player, target: Entity): Vec3 {
    val r = blastRadius.value
    val pack = p.level().getEntitiesOfClass(LivingEntity::class.java, target.boundingBox.inflate(r)) { e ->
        e.isAlive && e !is ArmorStand && e !is Bat && e != p && e !is Player && e.id !in ignored
    }.ifEmpty { listOf(target as? LivingEntity ?: return target.position()) }
    var x = 0.0; var y = Double.MAX_VALUE; var z = 0.0
    for (e in pack) {
        val lead = if (hypeLead.value) e.deltaMovement.multiply(3.0, 0.0, 3.0) else Vec3.ZERO
        x += e.x + lead.x; z += e.z + lead.z
        y = minOf(y, e.y)
    }
    return Vec3(x / pack.size, y, z / pack.size)
}

/** Wither Impact stops at the first block: only teleport when nothing solid is in the way. */
internal fun AutoRoutes.clearSight(p: Player, to: Vec3): Boolean {
    val from = p.eyePosition
    val hit = p.level().clip(net.minecraft.world.level.ClipContext(from, to.add(0.0, 0.5, 0.0), net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, p))
    return hit.type == net.minecraft.world.phys.HitResult.Type.MISS || hit.location.distanceTo(to) < 2.0
}

/** The mob to go for when clearing the whole room: the one with the most other mobs around it, then the closest. */
internal fun AutoRoutes.findRoomTarget(p: Player): Entity? {
    val room = DungeonScanner.currentRoom ?: return findTarget(p)
    val centres = room.roomComponents.map { it.vec2() }.toSet()
    val area = p.boundingBox.inflate(48.0, 20.0, 48.0)
    val mobs = p.level().getEntitiesOfClass(LivingEntity::class.java, area) { e ->
        e.isAlive && e.health > 0f && e !is ArmorStand && e !is Bat && e != p && e.id !in ignored &&
            e.javaClass.simpleName != "WitherBoss" &&
            (e !is Player || e.gameProfile.name in MINIBOSSES || e.name.string in MINIBOSSES || (campMode && isBloodMob(e))) &&
            ! e.name.string.contains("Watcher") &&
            DungeonScanner.getRoomCentre(e.blockX, e.blockZ) in centres
    }
    if (mobs.isEmpty()) return null
    val r2 = blastRadius.value * blastRadius.value
    val special = if (hypePriority.value) starredAndMinis(p, area) else emptySet()
    fun weight(e: LivingEntity) = if (e.id in special) 3 else 1
    return mobs.maxWithOrNull(compareBy<LivingEntity> { m -> mobs.filter { it.distanceToSqr(m) <= r2 }.sumOf(::weight) }.thenBy { - p.distanceToSqr(it) })
}

internal fun AutoRoutes.isBloodMob(e: Player): Boolean {
    if (e.isInvisible) return false
    val n = e.gameProfile.name
    return dev.riftwalk.utils.dungeons.DungeonListener.dungeonTeammates.none { it.name == n } && n != mc.player?.gameProfile?.name
}

/** Entity ids of starred mobs and minibosses in [area]. */
internal fun AutoRoutes.starredAndMinis(p: Player, area: AABB): Set<Int> {
    val level = p.level()
    val out = HashSet<Int>()
    for (stand in level.getEntitiesOfClass(ArmorStand::class.java, area)) {
        val name = stand.customName?.string ?: continue
        if (! name.contains("✯")) continue
        val offset = if (name.uppercase().contains("WITHERMANCER")) 3 else 1
        level.getEntity(stand.id - offset)?.takeIf { it !is ArmorStand }?.let { out += it.id }
    }
    for (other in level.getEntitiesOfClass(Player::class.java, area)) if (other != p && (other.gameProfile.name in MINIBOSSES || other.name.string in MINIBOSSES)) out += other.id
    return out
}

/**
 * Who is shooting us: the owner of an arrow / projectile close by, or failing that a visible skeleton-type mob.
 * Only returned when Hyperion can get to it (in teleport range with a clear line), otherwise null so the route goes on.
 */
internal fun AutoRoutes.findShooter(p: Player): Entity? {
    val level = p.level()
    val hasHype = useHype.value && PlayerUtils.findHotbarSlot { it.skyblockId in WITHER_BLADES } != null
    if (! hasHype) return null
    val fromArrow = level.getEntitiesOfClass(net.minecraft.world.entity.projectile.Projectile::class.java, p.boundingBox.inflate(4.0)) { true }
        .mapNotNull { it.owner as? LivingEntity }.firstOrNull { it != p && it.isAlive && it !is Player }
    val shooter = fromArrow ?: level.getEntitiesOfClass(LivingEntity::class.java, p.boundingBox.inflate(16.0)) { e ->
        e.isAlive && e !is Player && e !is ArmorStand && e.id !in ignored && e.javaClass.simpleName.contains("Skeleton") && p.hasLineOfSight(e)
    }.minByOrNull { it.distanceToSqr(p) }
    return shooter?.takeIf { it.id !in ignored && p.distanceTo(it) <= 11f && clearSight(p, it.position()) }
}

/** The closest hostile-looking mob within 3 blocks (something that can be hitting us). */
internal fun AutoRoutes.findAttacker(p: Player): Entity? = p.level().getEntitiesOfClass(LivingEntity::class.java, p.boundingBox.inflate(3.5)) { e ->
    e.isAlive && e.health > 0f && e !is ArmorStand && e !is Bat && e != p && (e !is Player || e.gameProfile.name in MINIBOSSES) &&
        e.distanceTo(p) <= 3.5f && e.id !in ignored && p.hasLineOfSight(e)
}.minByOrNull { it.distanceToSqr(p) }

internal fun AutoRoutes.findTarget(p: Player): Entity? {
    val range = maxOf(detourRange.value, attackRange.value)
    val level = p.level()
    val area = p.boundingBox.inflate(range + 2.0)
    val found = LinkedHashSet<Entity>()

    if (killStars.value) for (stand in level.getEntitiesOfClass(ArmorStand::class.java, area)) {
        val name = stand.customName?.string ?: continue
        if (! name.contains("✯")) continue
        val offset = if (name.uppercase().contains("WITHERMANCER")) 3 else 1
        val byId = level.getEntity(stand.id - offset)
        val mob = byId?.takeIf { it !is ArmorStand && it is LivingEntity }
            ?: level.getEntities(stand, stand.boundingBox.move(0.0, - 1.5, 0.0).inflate(0.6, 1.0, 0.6)) { it is LivingEntity && it !is ArmorStand && it != p }.minByOrNull { it.distanceToSqr(stand) }
        if (mob != null) found += mob
    }
    if (killMinis.value) for (other in level.getEntitiesOfClass(Player::class.java, area)) {
        if (other == p || other.isInvisible) continue
        if (other.gameProfile.name in MINIBOSSES || other.name.string in MINIBOSSES) found += other
    }

    return found.filter { it.id !in ignored && it.isAlive && (it as? LivingEntity)?.health?.let { h -> h > 0f } != false && p.distanceTo(it) <= range + 1.0 }
        .minByOrNull { p.distanceToSqr(it) }
}

//#endif
