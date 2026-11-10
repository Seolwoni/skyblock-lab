package dev.marceline.features.impl.fishing

//#if CHEAT
import kotlin.math.abs
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3

/*
 * Rare creature fight (Rare Creature Fight = Corner): what you'd do by hand against a Thunder or a Lord Jawbus -
 * get into a corner so a blast can't throw you into the lava, then keep casting the Hyperion: damage when it's
 * close, the Wither Shield when it isn't. The Lava Guard keeps running in the shared tick as always.
 */

/** The closest live rare creature of ours within Rare Fight Range, or null. */
internal fun AutoFishing.rareInPlay(p: LocalPlayer): LivingEntity? {
    if (rareFight.value != 1 || rareIds.isEmpty()) return null
    return rareIds.mapNotNull { p.level().getEntity(it) as? LivingEntity }
        .filter { it.isAlive && it.health > 0f && ! deadTag(it) && p.distanceTo(it) <= rareRange.value }
        .minByOrNull { it.distanceToSqr(p) }
}

/** The fight is over (dead, gone, out of range, too long): forget the corner so the next one finds its own. */
internal fun AutoFishing.endRareFight(why: String) {
    if (! cornerTried && corner == null) return
    journal.log("rare fight over: $why")
    corner = null; cornerTried = false; cornerPath = emptyList(); cornerStep = 0; cornerWalk = 0; rareFightTicks = 0
    stopWalking()
}

private fun AutoFishing.solidAt(b: BlockPos): Boolean {
    val level = mc.level ?: return false
    return ! level.getBlockState(b).getCollisionShape(level, b).isEmpty
}

/** Blocks along [d] from [feet] until lava at the feet or the floor below, or null within [look]. A wall stops the look. */
private fun AutoFishing.lavaRun(feet: BlockPos, d: CornerMath.Dir, look: Int = 6): Int? {
    for (i in 1 .. look) {
        val at = feet.offset(d.dx * i, 0, d.dz * i)
        if (isLava(at) || isLava(at.below()) || isLava(at.below(2))) return i
        if (solidAt(at) && solidAt(at.above())) return null
    }
    return null
}

/** The best corner within Corner Search that we can walk to, as a block centre; null when there is none. */
internal fun AutoFishing.findCorner(p: LocalPlayer): Vec3? {
    val feet = p.blockPosition()
    val r = cornerRange.value
    val spots = ArrayList<CornerMath.Spot>()
    for (dx in - r .. r) for (dz in - r .. r) for (dy in - 2 .. 2) {
        if (dx * dx + dz * dz > r * r) continue
        val b = feet.offset(dx, dy, dz)
        if (! standable(b) || nearLava(b, 1)) continue
        val walls = CornerMath.Dir.entries.filter { d ->
            val side = b.offset(d.dx, 0, d.dz)
            solidAt(side) && solidAt(side.above())
        }.toSet()
        if (! CornerMath.isCorner(walls)) continue
        val runs = CornerMath.Dir.entries.filter { it !in walls }.associateWith { lavaRun(b, it) }
        spots += CornerMath.Spot(b.x, b.y, b.z, walls, runs, Vec3.atBottomCenterOf(b).distanceTo(p.position()))
    }
    // the best few that a path actually reaches
    for (s in CornerMath.rank(spots).take(6)) {
        val goal = BlockPos(s.x, s.y, s.z)
        val path = if (goal == feet) listOf(goal) else findPath(p, goal) ?: continue
        cornerPath = path
        cornerStep = 0
        journal.log("rare fight: corner at ${goal.toShortString()}  walls=${s.walls}  ${"%.1f".format(s.dist)} away  (${spots.size} corners found)")
        return Vec3.atBottomCenterOf(goal)
    }
    journal.log("rare fight: no reachable corner within $r blocks (${spots.size} found)")
    return null
}

/** Walks the corner path node by node: face the node, then walk. True while still on the way. */
private fun AutoFishing.walkToCorner(p: LocalPlayer, goal: Vec3): Boolean {
    if (horizontalDist(p.position(), goal) < 0.45 && abs(p.y - goal.y) < 1.2) { stopWalking(); return false }
    if (++cornerWalk > 200) { // 10 s and not there: hold where we are
        journal.log("rare fight: couldn't reach the corner in 10 s, holding here")
        corner = null; cornerPath = emptyList(); stopWalking()
        return false
    }
    while (cornerStep < cornerPath.size - 1 && horizontalDist(p.position(), Vec3.atBottomCenterOf(cornerPath[cornerStep])) < 0.6) cornerStep++
    val node = if (cornerPath.isEmpty()) goal else Vec3.atBottomCenterOf(cornerPath[cornerStep])
    val last = cornerStep >= cornerPath.size - 1
    val yaw = Math.toDegrees(kotlin.math.atan2(node.z - p.z, node.x - p.x)).toFloat() - 90f
    ownMoveTicks = 20
    track = null
    val facing = turn(p, yaw, 12f, 25f)
    // the last step slowly (no sprint, short presses) so we stop in the corner, not past it
    val close = last && horizontalDist(p.position(), node) < 1.2
    mc.options.keyUp.isDown = facing && (! close || tickCount % 3 != 0)
    mc.options.keySprint.isDown = false
    mc.options.keyJump.isDown = p.onGround() && p.horizontalCollision && node.y > p.y + 0.5
    status = "walking to a corner"
    return true
}

/**
 * One tick of a rare fight. Returns false when it isn't ours to handle (Normal mode, no rare one around), so the
 * normal fight runs instead.
 */
internal fun AutoFishing.rareFightTick(p: LocalPlayer, rare: LivingEntity, mobs: List<LivingEntity>): Boolean {
    if (++rareFightTicks > rareMaxMin.value * 1200) {
        info("&eGave up on the ${rare.name.string} after ${rareMaxMin.value} min.")
        ignored += rare.id; rareIds -= rare.id
        endRareFight("time limit")
        return false
    }
    if (! cornerTried) {
        cornerTried = true
        corner = findCorner(p)
        if (corner == null) info("&eNo corner nearby - holding the ${rare.name.string} where you stand.")
        else info("&6${rare.name.string}&f: taking a corner.")
        if (p.fishing != null) rod()?.let { hold(it); use(p) }
    }
    corner?.let { if (walkToCorner(p, it)) return true }
    stopWalking()
    fight(p, (mobs + rare).distinct(), keepShield = true)
    if (! status.startsWith("rare")) status = "rare: $status"
    return true
}
//#endif
