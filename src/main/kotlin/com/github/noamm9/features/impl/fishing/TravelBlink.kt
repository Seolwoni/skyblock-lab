package com.github.noamm9.features.impl.fishing

//#if CHEAT
import com.github.noamm9.NoammAddons.mc
import com.github.noamm9.features.impl.fishing.TravelMath.Move
import com.github.noamm9.utils.HumanMotion
import com.github.noamm9.utils.PathMath.P
import com.github.noamm9.utils.PlayerUtils
import com.github.noamm9.utils.SkyblockTravel
import com.github.noamm9.utils.items.ItemUtils.skyblockId
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Teleport travel for Auto Fishing: finds the items, plans on a worker thread (TravelPlanner), and carries out one
 * teleport at a time like a hand would (stop, swap, aim with a fresh error, hold, use, settle, swap back).
 * Only used in TRAVEL. Wither Impact in fights fires through [fire] too, so its use is counted the same way.
 */
internal object TravelBlink {
    private enum class Phase { IDLE, PAUSE, SWAP, AIM, HOLD, WAIT_LAND, SETTLE, RESTORE }

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "Riftwalk travel planner").apply { isDaemon = true } }
    private val result = AtomicReference<Pair<Int, TravelPlanner.Plan?>?>(null)
    private var requestId = 0
    private var pendingId = - 1

    val book = TravelFail.Book()
    private var plan: TravelPlanner.Plan? = null
    private var planGoal: Vec3? = null
    private var walkOnly = false
    private var walkLast = 3

    private var phase = Phase.IDLE
    private var leg: TravelPlanner.Leg? = null
    private var wait = 0
    private var slot = - 1
    private var prevSlot = - 1
    private var aimYaw = 0f
    private var aimPitch = 0f
    private var errorScale = 1.0
    private var nextErrorScale = 1.0
    private var tripMisses = 0
    private var retries = 0
    private var firedFrom: Vec3 = Vec3.ZERO
    private var landedAt: Vec3? = null
    private var nextTeleportTick = 0
    private var gapLast = - 1
    private var gapBefore = - 1
    private var lastFireTick = - 1000

    // ------------------------------------------------------------------ items and costs

    /** Hotbar slot of an item that can do [move] (settings decide which ones may be used). */
    fun slotFor(move: Move): Int? = with(AutoFishing) {
        when (move) {
            Move.ETHERWARP -> if (! tpEther.value) null else PlayerUtils.findHotbarSlot { com.github.noamm9.utils.items.EtherwarpHelper.getEtherwarpDistance(it) != null }
            Move.BLINK -> PlayerUtils.findHotbarSlot { (tpAote.value && it.skyblockId == SkyblockTravel.AOTE) || (tpAotv.value && it.skyblockId == SkyblockTravel.AOTV) }
            Move.WALK -> null
        }
    }

    private fun manaFor(move: Move, slot: Int): Int {
        if (move == Move.ETHERWARP) return SkyblockTravel.ETHERWARP_MANA
        val id = mc.player?.inventory?.getItem(slot)?.skyblockId
        return if (id == SkyblockTravel.AOTV) SkyblockTravel.AOTV_MANA else SkyblockTravel.AOTE_MANA
    }

    /** Soulflow from the tab list ("Soulflow: 1,234"), or null when it isn't shown. */
    fun soulflow(): Int? = runCatching {
        SkyblockTravel.soulflowFrom(com.github.noamm9.utils.TabListUtils.getTabList().asSequence().map { it.first.string })
    }.getOrNull()

    /** Uses the held teleport / Wither Impact item (also used by the fights' Hyperion). */
    fun fire(p: LocalPlayer) {
        AutoFishing.use(p)
        lastFireTick = AutoFishing.tickCount
    }

    fun enabled() = with(AutoFishing) { tpAote.value || tpAotv.value || tpEther.value }

    /** Leaving TRAVEL in the middle of a teleport: drop it (no sneak left on). */
    fun abort() {
        if (phase == Phase.IDLE) return
        PlayerUtils.toggleSneak(false)
        phase = Phase.IDLE
        leg = null
        planGoal = null
    }

    fun reset() {
        book.reset()
        plan = null; planGoal = null; walkOnly = false
        phase = Phase.IDLE; leg = null
        pendingId = - 1; result.set(null)
        gapLast = - 1; gapBefore = - 1; nextTeleportTick = 0
    }

    // ------------------------------------------------------------------ planning (worker thread)

    private fun pOf(b: BlockPos) = P(b.x, b.y, b.z)
    private fun bp(p: P) = BlockPos(p.x, p.y, p.z)

    private val world = object : com.github.noamm9.utils.PathMath.World, TravelMath.Blocks {
        override fun standable(p: P) = AutoFishing.standable(bp(p))
        override fun headroom(p: P): Boolean {
            val l = mc.level ?: return false
            val up = bp(p).above(2)
            return l.getBlockState(up).getCollisionShape(l, up).isEmpty
        }
        override fun nearLava(p: P, r: Int) = AutoFishing.nearLava(bp(p), r)
        override fun solid(p: P): Boolean {
            val l = mc.level ?: return false
            val b = bp(p)
            return ! l.getBlockState(b).getCollisionShape(l, b).isEmpty && ! AutoFishing.isFluid(b)
        }
        override fun passable(p: P): Boolean {
            val l = mc.level ?: return false
            val b = bp(p)
            return l.getBlockState(b).getCollisionShape(l, b).isEmpty && ! AutoFishing.isFluid(b)
        }
        override fun lava(p: P) = AutoFishing.isLava(bp(p))
        override fun inWorld(y: Int) = mc.level?.isOutsideBuildHeight(y) == false
    }

    private fun options(): TravelPlanner.Options = with(AutoFishing) {
        val maxMana = com.github.noamm9.utils.ManaGuard.max
        TravelPlanner.Options(
            blink = slotFor(Move.BLINK) != null,
            ether = slotFor(Move.ETHERWARP) != null,
            maxChain = tpMaxChain.value,
            mana = com.github.noamm9.utils.ManaGuard.mana,
            manaReserve = tpManaReserve.value,
            blinkMana = SkyblockTravel.AOTE_MANA,
            etherMana = SkyblockTravel.ETHERWARP_MANA,
            soulflow = soulflow(),
            etherSoulflow = SkyblockTravel.ETHERWARP_SOULFLOW,
            // regen 2% of max mana per second = 0.1% per tick
            cost = TravelMath.CostModel(manaPerTick = ((maxMana ?: 500) * 0.001).coerceAtLeast(0.05), manaWeight = tpManaWeight.value),
            disabled = book.disabled.toSet(),
            walkLast = walkLast,
            blinkRange = SkyblockTravel.BLINK_RANGE,
            etherRange = SkyblockTravel.ETHERWARP_RANGE,
        )
    }

    private fun request(p: LocalPlayer, goal: Vec3) {
        val id = ++requestId
        pendingId = id
        val start = pOf(p.blockPosition())
        val g = pOf(BlockPos.containing(goal.x, goal.y + 0.1, goal.z))
        val o = options()
        worker.execute {
            val r = runCatching { TravelPlanner.plan(start, g, world, world, o) }.getOrNull() // a chunk unloading under us: walk
            result.set(id to r)
        }
    }

    // ------------------------------------------------------------------ TRAVEL

    /**
     * One TRAVEL tick. True when teleport travel handled the tick (a teleport going on, or waiting for a plan);
     * false lets the normal walking go on (along the planner's walking legs when it has them).
     */
    fun travelTick(p: LocalPlayer, goal: Vec3): Boolean = with(AutoFishing) {
        if (! enabled()) return false
        if (phase != Phase.IDLE) { step(p); return true }

        if (planGoal != goal) { // a new trip
            planGoal = goal; plan = null; walkOnly = false; tripMisses = 0; nextErrorScale = 1.0
            walkLast = HumanMotion.between(2, 3, RANDOM)
            request(p, goal)
        }
        result.getAndSet(null)?.let { (id, r) ->
            if (id == pendingId) {
                pendingId = - 1
                plan = r
                walkOnly = r == null || r.overBudget || r.teleports == 0
                if (r?.overBudget == true) journal.log("travel plan over its 8 ms budget - walking")
            }
        }
        val pl = plan
        if (walkOnly || pl == null) return false
        val k = pl.legs.indexOfFirst { it.move != Move.WALK }
        if (k < 0) return false
        val t = pl.legs[k]
        val feet = pOf(p.blockPosition())
        if (feet.distManhattan(t.from) <= 1 && kotlin.math.abs(feet.y - t.from.y) <= 1 && p.onGround()) {
            if (tickCount < nextTeleportTick) { stopWalking(); status = "between teleports"; return true }
            start(p, t)
            return true
        }
        // walk the planner's walking legs to where the teleport starts
        val nodes = pl.legs.subList(0, k).map { bp(it.to) }
        if (path.isEmpty() || path.last() != bp(t.from)) {
            path = nodes.ifEmpty { listOf(bp(t.from)) }
            pathIndex = 0
            pathGoal = goal
            repath = 100
        }
        return false
    }

    private fun start(p: LocalPlayer, t: TravelPlanner.Leg): Unit = with(AutoFishing) {
        prevSlot = - 1
        val s = slotFor(t.move) ?: return fail(p, t, TravelFail.Reason.LANDING, "no item for ${t.move}")
        leg = t
        slot = s
        prevSlot = p.inventory.selectedSlot
        retries = 0
        errorScale = nextErrorScale // half after a miss on this trip
        nextErrorScale = 1.0
        stopWalking()
        phase = Phase.PAUSE
        wait = HumanMotion.between(tpPauseMin.value, tpPauseMax.value, RANDOM)
        status = "${t.move.name.lowercase()} (${"%.0f".format(horizontalDist(p.position(), Vec3.atBottomCenterOf(bp(t.to))))} m)"
    }

    private fun drawAim(t: TravelPlanner.Leg): Unit = with(AutoFishing) {
        val e = TravelMath.aimError(tpAimMedian.value * errorScale, tpAimSpread.value, RANDOM)
        aimYaw = (t.yaw + e[0]).toFloat()
        aimPitch = (t.pitch + e[1]).toFloat().coerceIn(- 90f, 90f)
    }

    private fun step(p: LocalPlayer): Unit = with(AutoFishing) {
        val t = leg ?: run { phase = Phase.IDLE; return }
        ownMoveTicks = 30 // our own teleport: not the server's doing
        if (wait > 0) { wait--; return }
        when (phase) {
            Phase.PAUSE -> { // standing still a moment, then the item
                phase = Phase.SWAP
                wait = HumanMotion.between(tpSwapMin.value, tpSwapMax.value, RANDOM)
            }
            Phase.SWAP -> {
                hold(slot)
                expectedSlot = - 1
                if (t.move == Move.ETHERWARP) PlayerUtils.toggleSneak(true)
                drawAim(t)
                phase = Phase.AIM
            }
            Phase.AIM -> {
                if (! turn(p, aimYaw, aimPitch, 0.8f)) return
                com.github.noamm9.utils.Humanize.glide(p, aimYaw, aimPitch) // the last bit, spread over the frames
                if (t.move == Move.ETHERWARP && ! p.isCrouching) return // the sneak hasn't reached the server yet
                phase = Phase.HOLD
                wait = HumanMotion.between(tpHoldMin.value, tpHoldMax.value, RANDOM)
            }
            Phase.HOLD -> {
                val cost = manaFor(t.move, slot)
                if (! com.github.noamm9.utils.ManaGuard.canCast(cost) || ! TravelMath.affordable(com.github.noamm9.utils.ManaGuard.mana ?: Int.MAX_VALUE, cost, tpManaReserve.value))
                    return fail(p, t, TravelFail.Reason.MANA, "mana ${com.github.noamm9.utils.ManaGuard.mana ?: "?"} < $cost + reserve")
                if (t.move == Move.ETHERWARP) {
                    val sf = soulflow()
                    if (sf == null || sf < SkyblockTravel.ETHERWARP_SOULFLOW) return fail(p, t, TravelFail.Reason.SOULFLOW, "soulflow ${sf ?: "?"}")
                    val range = com.github.noamm9.utils.items.EtherwarpHelper.getEtherwarpDistance(p.inventory.getItem(slot)) ?: SkyblockTravel.ETHERWARP_RANGE.toDouble()
                    val land = com.github.noamm9.utils.items.EtherwarpHelper.getEtherPos(p.position(), Vec3.directionFromRotation(p.xRot, p.yRot), range)
                    if (! land.succeeded || ! standable(land.pos!!.above()) || nearLava(land.pos!!.above(), 1)) return fail(p, t, TravelFail.Reason.LANDING, "etherwarp would not land")
                }
                firedFrom = p.position()
                landedAt = null
                fire(p)
                phase = Phase.WAIT_LAND
                wait = 0
                waitLandTicks = 0
            }
            Phase.WAIT_LAND -> {
                waitLandTicks++
                val moved = p.position().distanceTo(firedFrom)
                if (landedAt == null && moved > 1.5) { landedAt = p.position(); waitLandTicks = 0; return }
                val at = landedAt
                if (at == null) {
                    if (waitLandTicks > 12) return fail(p, t, if (tickCount - lastFireTick < 3) TravelFail.Reason.COOLDOWN else TravelFail.Reason.LANDING, "didn't move")
                    return
                }
                // pulled back to where we fired from soon after: the server didn't take it
                if (waitLandTicks <= 6) {
                    if (p.position().distanceTo(firedFrom) < 1.2) return fail(p, t, TravelFail.Reason.RUBBERBAND, "snapped back")
                    return
                }
                // short of the plan is normal (aim error); way off or somewhere unsafe isn't
                val want = Vec3.atBottomCenterOf(bp(t.to))
                val off = horizontalDist(at, want)
                if (off > 2.0 + 0.5 * horizontalDist(firedFrom, want) || nearLava(BlockPos.containing(at), 1))
                    return fail(p, t, TravelFail.Reason.MISS, "landed ${"%.1f".format(off)} m off")
                landed(p)
            }
            Phase.SETTLE -> {
                phase = Phase.RESTORE
                wait = HumanMotion.between(tpSwapMin.value, tpSwapMax.value, RANDOM)
            }
            Phase.RESTORE -> {
                if (prevSlot in 0 .. 8) hold(prevSlot)
                expectedSlot = - 1
                phase = Phase.IDLE
                leg = null
            }
            Phase.IDLE -> {}
        }
    }

    private var waitLandTicks = 0

    private fun landed(p: LocalPlayer): Unit = with(AutoFishing) {
        val t = leg!!
        PlayerUtils.toggleSneak(false)
        journal.log("travel ${t.move.name.lowercase()}  ${"%.1f".format(horizontalDist(firedFrom, p.position()))} m  to ${p.blockPosition().toShortString()}")
        val g = TravelMath.blinkGap(tpGapMedian.value, tpGapSpread.value, 1, 20, gapLast, gapBefore, RANDOM)
        gapBefore = gapLast; gapLast = g
        nextTeleportTick = tickCount + g
        // don't look at the next thing straight away
        phase = Phase.SETTLE
        wait = HumanMotion.between(tpSettleMin.value, tpSettleMax.value, RANDOM)
        afterTeleport()
    }

    /** A teleport ended (landed or failed): plan again from where we are. */
    private fun afterTeleport() {
        val g = planGoal
        plan = null
        planGoal = null
        AutoFishing.path = emptyList()
        AutoFishing.repath = 0
        if (g != null) mc.player?.let { request(it, g); planGoal = g }
    }

    private fun fail(p: LocalPlayer, t: TravelPlanner.Leg, reason: TravelFail.Reason, detail: String): Unit = with(AutoFishing) {
        val out = book.record(t.move, t.from, t.to, reason)
        journal.log(TravelFail.line(reason, pOf(p.blockPosition()), t.move, retries, out.fallback) + "  ($detail${if (out.disabled) ", edge off" else ""})")
        PlayerUtils.toggleSneak(false)
        // still standing where we fired from (cooldown): the same teleport once more after a moment
        if (reason == TravelFail.Reason.COOLDOWN && out.retry && retries < 1) {
            retries++
            phase = Phase.PAUSE
            wait = HumanMotion.between(tpPauseMin.value, tpPauseMax.value, RANDOM) + 10
            return
        }
        phase = if (prevSlot in 0 .. 8) Phase.RESTORE else Phase.IDLE
        wait = HumanMotion.between(tpSwapMin.value, tpSwapMax.value, RANDOM)
        when {
            // a miss: plan again from where we came down, next teleport with half the aim error; a second miss walks
            reason == TravelFail.Reason.MISS && ++tripMisses == 1 -> { nextErrorScale = out.errorScale; afterTeleport() }
            reason == TravelFail.Reason.MISS || out.fallback -> walkTrip()
            else -> afterTeleport() // rubberband: the edge is off, plan around it
        }
    }

    /** The rest of this trip is walked (normal walking, no more plans until the goal changes). */
    private fun walkTrip() {
        walkOnly = true
        plan = null
        pendingId = - 1
        planGoal = AutoFishing.stand
        AutoFishing.path = emptyList()
        AutoFishing.repath = 0
    }
}
//#endif
