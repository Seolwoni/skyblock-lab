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

// Getting to the fish: finding Hotspots, a lava-safe A* walk with rounded corners, and etherwarp / AOTV hops.

internal fun AutoFishing.nearestHotspot(p: LocalPlayer): Entity? {
    val r = hotspotRange.value.toDouble()
    // the "HOTSPOT" text: an armor stand's name or a text display, whichever the server uses
    val live = com.github.noamm9.utils.FloatingText.near(p.level(), p.boundingBox.inflate(r, 64.0, r))
        .filter { (e, t) -> e.isAlive && t.contains(com.github.noamm9.utils.SkyblockText.HOTSPOT, ignoreCase = true) }
        .map { it.first }
    val now = System.currentTimeMillis()
    for (h in live) remembered[h.blockPosition().asLong()] = h.position() to now
    return live.filter { it.id !in badHotspots }.minByOrNull { it.distanceToSqr(p) }
}

/** A hotspot seen earlier that is out of entity range now (remembered for 3 minutes). */
internal fun AutoFishing.rememberedHotspot(p: LocalPlayer): Vec3? {
    val now = System.currentTimeMillis()
    remembered.entries.removeIf { now - it.value.second > 180_000 }
    return remembered.values.map { it.first }.filter { it.distanceTo(p.position()) > 10 }.minByOrNull { it.distanceTo(p.position()) }
}

internal fun AutoFishing.findHotspot(p: LocalPlayer) {
    if (! hotspots.value) return goToFluid(p)
    val hs = nearestHotspot(p)
    if (hs == null) {
        // none in sight: head for one seen earlier, it'll show up once we're close
        val far = rememberedHotspot(p)
        val spot = far?.let { shoreSpot(p, it) }
        if (far != null && spot != null) {
            hotspot = null
            stand = spot
            scouting = true
            path = emptyList()
            travelStuck = 0
            state = FishingState.TRAVEL
            info("&7Heading for a hotspot seen earlier &8(${p.position().distanceTo(far).toInt()} blocks)")
            return
        }
        if (far != null) {
            // a guess or a shared spot with no shore by it: walk to ground near it and look from there
            val ground = groundNear(far)
            remembered.remove(BlockPos.containing(far).asLong())
            if (ground != null) {
                hotspot = null
                stand = ground
                scouting = true
                path = emptyList()
                travelStuck = 0
                state = FishingState.TRAVEL
                return
            }
        }
        // nothing known: ask the radar (busy for a moment while it reads)
        if (radarStep(p)) return
        hotspot = null
        status = "no hotspot nearby"
        goToFluid(p)
        return
    }
    scouting = false
    val spot = shoreSpot(p, hs.position())
    if (spot == null) {
        badHotspots += hs.id
        status = "no place to stand by that hotspot"
        return
    }
    hotspot = hs
    hotspotAt = hs.position()
    hotspotsVisited++
    stand = spot
    aimTries = 0
    travelStuck = 0
    path = emptyList()
    state = FishingState.TRAVEL
    info("&7Hotspot found &8(${p.position().distanceTo(hs.position()).toInt()} blocks)&7, going there.")
}

/** A block on the shore 4-10 blocks from the hotspot, with a clear view of it, as close to us as possible. */
internal fun AutoFishing.shoreSpot(p: LocalPlayer, centre: Vec3): Vec3? {
    val level = p.level()
    val c = BlockPos.containing(centre)
    var best: Vec3? = null
    var bestScore = Double.MAX_VALUE
    for (dx in - 10 .. 10) for (dz in - 10 .. 10) {
        val flat = kotlin.math.sqrt((dx * dx + dz * dz).toDouble())
        if (flat < 4.0 || flat > 10.0) continue
        for (dy in - 2 .. 5) {
            val feet = BlockPos(c.x + dx, c.y + dy, c.z + dz)
            val below = feet.below()
            if (level.getBlockState(below).getCollisionShape(level, below).isEmpty) continue
            if (! level.getFluidState(below).isEmpty) continue
            if (! level.getBlockState(feet).getCollisionShape(level, feet).isEmpty || ! level.getFluidState(feet).isEmpty) continue
            if (! level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty) continue
            if (nearLava(feet, 1)) continue // never stand right at a lava edge
            val eye = Vec3(feet.x + 0.5, feet.y + 1.62, feet.z + 0.5)
            val clip = level.clip(net.minecraft.world.level.ClipContext(eye, centre.add(0.0, 0.3, 0.0), net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, p))
            if (clip.type != net.minecraft.world.phys.HitResult.Type.MISS && clip.location.distanceTo(centre) > 1.5) continue
            val pos = Vec3(feet.x + 0.5, feet.y.toDouble(), feet.z + 0.5)
            val score = pos.distanceTo(p.position()) + abs(flat - 7.0) * 2
            if (score < bestScore) { bestScore = score; best = pos }
            break
        }
    }
    return best
}

internal fun AutoFishing.travel(p: LocalPlayer) {
    val goal = stand ?: return run { state = FishingState.FIND }
    ownMoveTicks = 20 // our own walking / AOTV: not the server's doing
    expectedSlot = - 1
    val hd = horizontalDist(p.position(), goal)
    // there; or right above / below it and not getting any closer for 2 s: take where we stand as the spot
    if (hd < 0.8 && abs(p.y - goal.y) < 2.5) nearGoalTicks++ else nearGoalTicks = 0
    if ((hd < 0.6 && abs(p.y - goal.y) < 1.5) || nearGoalTicks > 40) {
        stopWalking()
        path = emptyList()
        if (nearGoalTicks > 40) journal.log("close enough to the spot (${"%.1f".format(hd)}), fishing from here")
        nearGoalTicks = 0
        arrive(p)
        return
    }
    // a hop in progress
    if (hop != null) return doHop(p)
    if (hopWait > 0) hopWait--

    val flat = horizontalDist(p.position(), goal)
    val rise = goal.y - p.y
    if (pathGoal != goal) { walkBest = Double.MAX_VALUE; walkNoProgress = 0; walkReplans = 0 }
    if (pathGoal != goal || --repath <= 0) {
        pathGoal = goal
        path = if (flat < 70) findPath(p, BlockPos.containing(goal.x, goal.y + 0.1, goal.z)) ?: emptyList() else emptyList()
        pathIndex = 0
        repath = 100
    }
    // far, high, or no walkable way: plan an etherwarp / AOTV hop, checking where it lands
    val needHop = path.isEmpty() || flat > 14 || rise > 3
    if (useEther.value && needHop && hopWait <= 0 && p.onGround() && --planIn <= 0) {
        planIn = 6
        val walkable = path.isNotEmpty()
        if (! walkable || RANDOM.nextInt(100) >= walkSome.value) planHop(p, goal)?.let {
            hop = it
            hopPhase = 0
            hopTimer = 0
            stopWalking()
            status = (if (it.ether) "etherwarp" else "AOTV") + " (${p.position().distanceTo(goal).toInt()}m left)"
            return doHop(p)
        }
    }
    if (path.isEmpty()) {
        stopWalking()
        if (planIn == 6 && ++travelStuck > 25) {
            hotspot?.let { badHotspots += it.id }
            hotspot = null
            scouting = false
            state = FishingState.FIND
            status = "no safe way there"
        }
        else status = "planning a way there"
        return
    }
    status = "going to ${if (hotspot != null) "the hotspot" else "the spot"} (${p.position().distanceTo(goal).toInt()}m)"
    walkPath(p)
}

internal fun AutoFishing.arrive(p: LocalPlayer) {
    if (scouting) { // got near a remembered hotspot: look for it properly now
        scouting = false
        remembered.clear()
        state = FishingState.FIND
        return
    }
    val hs = hotspot
    val ft = fishTarget
    if (hs != null) aimAt(p, hs) else if (ft != null) aimAtPoint(p, ft) else aimTarget = null
    offYaw = 0f; offPitch = 0f; moveLen = 0
    // the spot is where we really stand now (block centre), so "pushed away" and centring agree on it
    stand = Vec3.atBottomCenterOf(BlockPos.containing(p.position()))
    arrivedAt = tickCount
    returning = true
    state = FishingState.CAST
    wait = castWait()
    status = "at the spot"
}

internal fun AutoFishing.horizontalDist(a: Vec3, b: Vec3) = kotlin.math.sqrt((a.x - b.x) * (a.x - b.x) + (a.z - b.z) * (a.z - b.z))

internal fun AutoFishing.stopWalking() {
    mc.options.keyUp.isDown = false
    mc.options.keyJump.isDown = false
    mc.options.keySprint.isDown = false
}

internal fun AutoFishing.isLava(pos: BlockPos) = mc.level?.getFluidState(pos)?.`is`(FluidTags.LAVA) == true

internal fun AutoFishing.isFluid(pos: BlockPos) = mc.level?.getFluidState(pos)?.isEmpty == false

/** Lava within [r] blocks around (and just below) the feet. */
internal fun AutoFishing.nearLava(feet: BlockPos, r: Int): Boolean {
    for (dx in - r .. r) for (dz in - r .. r) for (dy in - 2 .. 0) if (isLava(feet.offset(dx, dy, dz))) return true
    return false
}

internal fun AutoFishing.standable(feet: BlockPos): Boolean {
    val level = mc.level ?: return false
    val below = feet.below()
    if (level.getBlockState(below).getCollisionShape(level, below).isEmpty || isFluid(below)) return false
    if (! level.getBlockState(feet).getCollisionShape(level, feet).isEmpty || isFluid(feet)) return false
    val head = feet.above()
    return level.getBlockState(head).getCollisionShape(level, head).isEmpty && ! isFluid(head)
}

/** A* over standable blocks: never steps into or next to lava, keeps a block's distance from edges when it can (PathMath). */
internal fun AutoFishing.findPath(p: LocalPlayer, goal: BlockPos): List<BlockPos>? {
    val start = p.blockPosition()
    fun bp(q: com.github.noamm9.utils.PathMath.P) = BlockPos(q.x, q.y, q.z)
    val world = object : com.github.noamm9.utils.PathMath.World {
        override fun standable(p: com.github.noamm9.utils.PathMath.P) = this@findPath.standable(bp(p))
        override fun headroom(p: com.github.noamm9.utils.PathMath.P): Boolean {
            val up = bp(p).above(2)
            return mc.level!!.getBlockState(up).getCollisionShape(mc.level!!, up).isEmpty
        }
        override fun nearLava(p: com.github.noamm9.utils.PathMath.P, r: Int) = this@findPath.nearLava(bp(p), r)
    }
    return com.github.noamm9.utils.PathMath.aStar(com.github.noamm9.utils.PathMath.P(start.x, start.y, start.z), com.github.noamm9.utils.PathMath.P(goal.x, goal.y, goal.z), world)?.map { bp(it) }
}

/** Walks the path node to node with eased turns; hops along long safe straight stretches with the AOTV. */
internal fun AutoFishing.walkPath(p: LocalPlayer) {
    if (pathIndex >= path.size) return
    // skip nodes we've already passed
    // skip nodes we're at or have already gone past (the next one is closer than this one) - no circling a node
    while (pathIndex < path.size - 1) {
        val here = horizontalDist(p.position(), Vec3.atBottomCenterOf(path[pathIndex]))
        val next = horizontalDist(p.position(), Vec3.atBottomCenterOf(path[pathIndex + 1]))
        if (here < 1.0 || (next < here && path[pathIndex + 1].y == path[pathIndex].y && here < 2.5)) pathIndex++ else break
    }
    // not getting any closer to the goal for 3 s while walking: plan again from here (orbiting / stuck)
    val goalNow = stand
    if (goalNow != null) {
        val d = horizontalDist(p.position(), goalNow)
        if (d < walkBest - 0.5) { walkBest = d; walkNoProgress = 0 }
        else if (++walkNoProgress > 60) {
            journal.log("no progress for 3 s (${"%.1f".format(d)} left), planning again")
            walkNoProgress = 0; walkBest = Double.MAX_VALUE
            stopWalking(); repath = 0; path = emptyList()
            if (++walkReplans >= 4) { walkReplans = 0; hopWait = 0; planIn = 0 } // let a hop take over
            return
        }
    }
    val node = path[pathIndex]
    val target = Vec3.atBottomCenterOf(node)

    // safety: never walk into a block that has lava in it or under it
    val ahead = BlockPos.containing(p.x + p.deltaMovement.x * 4, p.y - 0.5, p.z + p.deltaMovement.z * 4)
    if (isLava(ahead) || isLava(ahead.above()) || (mc.level!!.getBlockState(ahead).isAir && isLava(ahead.below()))) {
        stopWalking()
        repath = 0
        status = "lava ahead, finding another way"
        return
    }

    if (hopWait > 0) hopWait--
    if (tryHop(p)) return

    // steer at a point a few blocks further along the path (rounds the corners), the view gliding after it
    // every frame; falls back to the next node when cutting the corner would cross anything unsafe
    val look = lookAhead(p) ?: target
    val yaw = Math.toDegrees(kotlin.math.atan2(look.z - p.z, look.x - p.x)).toFloat() - 90f
    if (walkPitch == 0f || RANDOM.nextInt(120) == 0) walkPitch = 7f + RANDOM.nextFloat() * 8f
    walkGoalYaw = yaw
    walkGoalPitch = walkPitch
    walkSteer = true
    ownMoveTicks = 20
    val dy = node.y - p.blockPosition().y
    // face it first, then walk: walking while still turning hard is what draws circles
    val off = abs(wrap(yaw - p.yRot))
    mc.options.keyUp.isDown = off < 35f
    mc.options.keyJump.isDown = p.onGround() && (dy > 0 || p.horizontalCollision)
    mc.options.keySprint.isDown = off < 12f && path.size - pathIndex > 4 && ! nearLava(p.blockPosition(), 2)
}

/** The point ~2.5 blocks ahead along the path, if the straight line there is safe ground all the way. */
internal fun AutoFishing.lookAhead(p: LocalPlayer): Vec3? {
    var left = 2.3 + RANDOM.nextDouble() * 0.6
    var prev = p.position()
    val y0 = p.blockPosition().y
    for (i in pathIndex until path.size) {
        val c = Vec3.atBottomCenterOf(path[i])
        if (path[i].y != y0) return if (i == pathIndex) null else prev // steps / drops: go node by node
        val seg = horizontalDist(prev, c)
        if (seg >= left) {
            val pt = prev.add((c.x - prev.x) * left / seg, 0.0, (c.z - prev.z) * left / seg)
            return if (lineSafe(p.position(), pt)) pt else null
        }
        left -= seg
        prev = c
    }
    return if (lineSafe(p.position(), prev)) prev else null
}

/** Solid, lava-free ground under every step of the straight line (and a block of margin from lava). */
internal fun AutoFishing.lineSafe(a: Vec3, b: Vec3): Boolean {
    val lvl = mc.level ?: return false
    val n = (horizontalDist(a, b) / 0.3).toInt().coerceAtLeast(1)
    for (i in 0 .. n) {
        val t = i / n.toDouble()
        val bp = BlockPos.containing(a.x + (b.x - a.x) * t, a.y + 0.1, a.z + (b.z - a.z) * t)
        val under = bp.below()
        if (lvl.getBlockState(under).getCollisionShape(lvl, under).isEmpty) return false
        if (! lvl.getBlockState(bp).getCollisionShape(lvl, bp).isEmpty) return false
        if (isLava(bp) || isLava(under) || nearLava(bp, 1)) return false
    }
    return true
}

/** AOTV to a path node 9-12 blocks ahead when the stretch is straight, level, in sight and away from lava. */
internal fun AutoFishing.tryHop(p: LocalPlayer): Boolean {
    if (hopWait > 0 || ! p.onGround() || RANDOM.nextInt(100) < walkSome.value) return false
    val slot = PlayerUtils.findHotbarSlot { it.skyblockId in com.github.noamm9.utils.SkyblockText.TELEPORT_SWORDS } ?: return false
    val from = p.position()
    var pick = - 1
    for (i in pathIndex until path.size) {
        val d = horizontalDist(from, Vec3.atBottomCenterOf(path[i]))
        if (d > 12.0) break
        if (abs(path[i].y - p.blockPosition().y) > 1) break
        if (d >= 9.0) pick = i
    }
    if (pick < 0) return false
    val node = path[pick]
    if (nearLava(node, 1)) return false
    // every node on the way has to sit on the straight line (no corner to cut over a gap)
    val end = Vec3.atBottomCenterOf(node)
    for (i in pathIndex .. pick) {
        val c = Vec3.atBottomCenterOf(path[i])
        val t = ((c.x - from.x) * (end.x - from.x) + (c.z - from.z) * (end.z - from.z)) / ((end.x - from.x).let { it * it } + (end.z - from.z).let { it * it }).coerceAtLeast(0.01)
        val px = from.x + (end.x - from.x) * t
        val pz = from.z + (end.z - from.z) * t
        if (kotlin.math.sqrt((c.x - px) * (c.x - px) + (c.z - pz) * (c.z - pz)) > 1.2) return false
    }
    val aim = end.add(0.0, 1.0, 0.0)
    val clip = p.level().clip(net.minecraft.world.level.ClipContext(p.eyePosition, aim, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, p))
    if (clip.type != net.minecraft.world.phys.HitResult.Type.MISS) return false
    hold(slot)
    stopWalking()
    val rot = MathUtils.calcYawPitch(aim)
    if (! turn(p, rot.yaw, rot.pitch, 2f)) return true
    use(p)
    hopWait = delay(hopMin.value, hopMax.value)
    pathIndex = pick
    return true
}

/** How good standing at [feet] is on the way to [goal] (lower is better). */
internal fun AutoFishing.hopScore(feet: Vec3, goal: Vec3) = horizontalDist(feet, goal) + abs(goal.y - feet.y) * 1.2

/**
 * Picks the next hop towards [goal]:
 * etherwarp - top faces of solid ground near the line to the goal (and the goal itself), each checked with
 * Noamm's etherwarp prediction from a sneaking eye, never next to lava;
 * AOTV - a few aims towards the goal, each traced to where the teleport stops and where we'd fall to.
 * Only hops that get clearly closer count.
 */
internal fun AutoFishing.planHop(p: LocalPlayer, goal: Vec3): AutoFishing.Hop? {
    val here = hopScore(p.position(), goal)
    val etherSlot = PlayerUtils.findHotbarSlot { com.github.noamm9.utils.items.EtherwarpHelper.getEtherwarpDistance(it) != null }
    if (etherSlot != null) {
        val range = com.github.noamm9.utils.items.EtherwarpHelper.getEtherwarpDistance(p.inventory.getItem(etherSlot)) ?: 57.0
        val sneakFeet = if (p.isCrouching) p.position() else p.position().subtract(0.0, 0.35, 0.0)
        val cands = LinkedHashSet<BlockPos>()
        cands += BlockPos.containing(goal.x, goal.y - 0.5, goal.z)
        val dir = goal.subtract(p.position())
        for (step in 1 .. 14) {
            val t = step / 14.0
            val pt = p.position().add(dir.scale(t))
            for (ox in - 4 .. 4 step 2) for (oz in - 4 .. 4 step 2) {
                topBlock(BlockPos.containing(pt.x + ox, maxOf(pt.y, goal.y) + 6, pt.z + oz), 16)?.let { cands += it }
            }
        }
        var best: AutoFishing.Hop? = null
        var bestScore = here - 6.0
        var checked = 0
        for (b in cands) {
            if (checked > 220) break
            val feet = b.above()
            if (! standable(feet) || nearLava(feet, 1)) continue
            val top = Vec3(b.x + 0.5, b.y + 0.98, b.z + 0.5)
            if (top.distanceTo(p.eyePosition) > range) continue
            val score = hopScore(Vec3.atBottomCenterOf(feet), goal)
            if (score >= bestScore) continue
            checked++
            val rot = MathUtils.calcYawPitch(top)
            val look = Vec3.directionFromRotation(rot.pitch, rot.yaw)
            val land = com.github.noamm9.utils.items.EtherwarpHelper.getEtherPos(sneakFeet, look, range)
            if (! land.succeeded || land.pos != b) continue
            bestScore = score
            best = AutoFishing.Hop(true, etherSlot, b, rot.yaw, rot.pitch, Vec3.atBottomCenterOf(feet))
        }
        if (best != null) return best
    }
    val aotvSlot = PlayerUtils.findHotbarSlot { it.skyblockId in com.github.noamm9.utils.SkyblockText.TELEPORT_SWORDS } ?: return null
    val baseYaw = Math.toDegrees(kotlin.math.atan2(goal.z - p.z, goal.x - p.x)).toFloat() - 90f
    var best: AutoFishing.Hop? = null
    var bestScore = here - 4.0
    for (dyaw in floatArrayOf(0f, - 12f, 12f, - 25f, 25f)) for (pitch in floatArrayOf(- 5f, - 20f, - 35f, - 50f, 5f)) {
        val yaw = baseYaw + dyaw
        val land = aotvLanding(p, yaw, pitch) ?: continue
        val score = hopScore(land, goal)
        if (score < bestScore) {
            bestScore = score
            best = AutoFishing.Hop(false, aotvSlot, null, yaw, pitch, land)
        }
    }
    return best
}

/** The highest solid block with two free blocks over it, scanning down [depth] blocks from [from]. */
internal fun AutoFishing.topBlock(from: BlockPos, depth: Int): BlockPos? {
    val level = mc.level ?: return null
    var b = from
    repeat(depth) {
        val below = b.below()
        if (! level.getBlockState(below).getCollisionShape(level, below).isEmpty) return if (isFluid(below)) null else below
        if (isFluid(below)) return null
        b = below
    }
    return null
}

/** Where an AOTV with this aim leaves us standing: the teleport stops before the first block, then we fall. Null if unsafe. */
internal fun AutoFishing.aotvLanding(p: LocalPlayer, yaw: Float, pitch: Float): Vec3? {
    val look = Vec3.directionFromRotation(pitch, yaw)
    val eye = p.eyePosition
    val end = eye.add(look.scale(aotvRange.value.toDouble()))
    val hit = p.level().clip(net.minecraft.world.level.ClipContext(eye, end, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, p))
    val stop = if (hit.type == net.minecraft.world.phys.HitResult.Type.MISS) end else hit.location.subtract(look.scale(0.6))
    if (stop.distanceTo(eye) < 5.0) return null
    var feet = BlockPos.containing(stop.x, stop.y - 1.6, stop.z)
    repeat(8) { // fall to the ground
        if (isFluid(feet) || isFluid(feet.below())) return null
        if (standable(feet)) return if (nearLava(feet, 1)) null else Vec3.atBottomCenterOf(feet)
        feet = feet.below()
    }
    return null
}

internal fun AutoFishing.doHop(p: LocalPlayer) {
    val h = hop ?: return
    ownMoveTicks = 30
    hold(h.slot)
    when (hopPhase) {
        0 -> { // sneak (etherwarp) or stand (AOTV), turn onto the aim
            PlayerUtils.toggleSneak(h.ether)
            if (! turn(p, h.yaw, h.pitch, 0.6f)) return
            com.github.noamm9.utils.Humanize.setView(p, h.yaw, h.pitch)
            if (h.ether != p.isCrouching) return // the sneak hasn't reached the server yet
            if (h.ether) { // check once more from where we really are
                val range = com.github.noamm9.utils.items.EtherwarpHelper.getEtherwarpDistance(p.inventory.getItem(h.slot)) ?: 57.0
                val land = com.github.noamm9.utils.items.EtherwarpHelper.getEtherPos(p.position(), Vec3.directionFromRotation(h.pitch, h.yaw), range)
                if (! land.succeeded || land.pos != h.target) { hop = null; PlayerUtils.toggleSneak(false); planIn = 0; return }
            }
            hopTimer = 0
            hopFrom = p.position()
            use(p)
            hopPhase = 1
        }
        else -> { // wait for the teleport
            if (p.position().distanceTo(hopFrom) > 2.0 || ++hopTimer > 15) {
                PlayerUtils.toggleSneak(false)
                hop = null
                hopWait = delay(hopMin.value, hopMax.value)
                repath = 0
                planIn = 0
            }
        }
    }
}

/** Walking: the same spring, a bit softer, after the steering point. */
internal fun AutoFishing.steerFrame(p: LocalPlayer, dt: Float) {
    if (dt <= 0f) return
    val errY = wrap(walkGoalYaw - p.yRot)
    val errP = walkGoalPitch - p.xRot
    val k = 60f
    val c = 2f * kotlin.math.sqrt(k)
    trackVelYaw += (k * errY - c * trackVelYaw) * dt
    trackVelPitch += (k * errP - c * trackVelPitch) * dt
    val max = 380f
    val sp = kotlin.math.hypot(trackVelYaw.toDouble(), trackVelPitch.toDouble()).toFloat()
    if (sp > max) { trackVelYaw *= max / sp; trackVelPitch *= max / sp }
    com.github.noamm9.utils.Humanize.nudgeView(p, trackVelYaw * dt, trackVelPitch * dt)
}

//#endif
