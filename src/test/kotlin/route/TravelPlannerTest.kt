package route

import dev.marceline.features.impl.fishing.TravelFail
import dev.marceline.features.impl.fishing.TravelFail.Reason
import dev.marceline.features.impl.fishing.TravelMath
import dev.marceline.features.impl.fishing.TravelMath.Move
import dev.marceline.features.impl.fishing.TravelPlanner
import dev.marceline.utils.PathMath
import dev.marceline.utils.PathMath.P
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TravelPlannerTest {
    /** A floor (y = 0) from x 0..90, z -6..6; feet at y = 1. Same world for walking and for teleports. */
    private class Field : PathMath.World, TravelMath.Blocks {
        val extra = HashSet<P>()
        val lavas = HashSet<P>()
        fun floor(p: P) = p.y == 0 && p.x in 0 .. 90 && p.z in -6 .. 6
        override fun solid(p: P) = floor(p) || p in extra
        override fun passable(p: P) = ! solid(p) && p !in lavas
        override fun lava(p: P) = p in lavas
        override fun inWorld(y: Int) = y in 0 .. 255
        override fun standable(p: P) = passable(p) && passable(p.offset(0, 1, 0)) && solid(p.offset(0, -1, 0))
        override fun headroom(p: P) = passable(p.offset(0, 2, 0))
        override fun nearLava(p: P, r: Int): Boolean {
            for (dx in -r .. r) for (dz in -r .. r) for (dy in -2 .. 0) if (p.offset(dx, dy, dz) in lavas) return true
            return false
        }
    }

    private val start = P(0, 1, 0)
    private val goal = P(60, 1, 0)
    /** Plenty of mana regen, so teleports beat walking unless something says otherwise. */
    private val rich = TravelMath.CostModel(walkSpeed = 0.28, teleportTicks = 8.0, manaPerTick = 20.0, manaWeight = 1.0, soulflowWeight = 40.0)
    private fun opts(blink: Boolean = true, ether: Boolean = true, mana: Int? = 2000, soul: Int? = 100, reserve: Int = 100, cost: TravelMath.CostModel = rich) =
        TravelPlanner.Options(blink = blink, ether = ether, mana = mana, manaReserve = reserve, soulflow = soul, cost = cost, budgetNanos = Long.MAX_VALUE, maxChain = 3)

    private fun plan(w: Field = Field(), o: TravelPlanner.Options = opts()) = assertNotNull(TravelPlanner.plan(start, goal, w, w, o), "no plan")

    // ---- 3-step fallback: etherwarp -> AOTE -> walk

    @Test fun etherwarpWhenItCan() {
        val p = plan()
        assertTrue(p.legs.any { it.move == Move.ETHERWARP }, "no etherwarp: ${p.legs.map { it.move }}")
    }

    @Test fun etherwarpBlockedMeansBlinks() {
        val noSoul = plan(o = opts(soul = null))
        assertTrue(noSoul.legs.none { it.move == Move.ETHERWARP }, "etherwarp without soulflow")
        assertTrue(noSoul.legs.any { it.move == Move.BLINK }, "no blink fallback: ${noSoul.legs.map { it.move }}")
        val zeroSoul = plan(o = opts(soul = 0))
        assertTrue(zeroSoul.legs.none { it.move == Move.ETHERWARP })
    }

    @Test fun blinkBlockedToMeansWalking() {
        // mana would fall under the reserve: no teleports at all
        val p = plan(o = opts(mana = 120, reserve = 100, soul = null))
        assertTrue(p.legs.isNotEmpty() && p.legs.all { it.move == Move.WALK }, "${p.legs.map { it.move }}")
        val off = plan(o = opts(blink = false, ether = false))
        assertTrue(off.legs.all { it.move == Move.WALK })
    }

    // ---- cost monotonic in the mana weight

    @Test fun manaWeightMakesBlinksDearerThanWalking() {
        var last = -1.0
        var crossed = false
        for (i in 0 .. 40) {
            val m = rich.copy(manaPerTick = 1.0, manaWeight = i * 0.05)
            val blink = TravelMath.cost(Move.BLINK, 8.0, 50, 0, m)
            assertTrue(blink >= last, "blink cost went down as the weight went up")
            last = blink
            if (blink > TravelMath.cost(Move.WALK, 8.0, 0, 0, m)) crossed = true
        }
        assertTrue(crossed, "never got dearer than walking")
        // and the planner follows: a heavy weight walks
        val heavy = plan(o = opts(soul = null, cost = rich.copy(manaPerTick = 1.0, manaWeight = 2.0)))
        assertTrue(heavy.legs.all { it.move == Move.WALK }, "${heavy.legs.map { it.move }}")
    }

    // ---- shape of the plan

    @Test fun lastBlocksAreWalked() {
        for (o in listOf(opts(), opts(soul = null))) {
            val p = plan(o = o)
            for (leg in p.legs.filter { it.move != Move.WALK }) {
                val fromFlat = kotlin.math.hypot((leg.from.x - goal.x).toDouble(), (leg.from.z - goal.z).toDouble())
                val toFlat = kotlin.math.hypot((leg.to.x - goal.x).toDouble(), (leg.to.z - goal.z).toDouble())
                assertTrue(fromFlat > o.walkLast, "teleported from ${"%.1f".format(fromFlat)} blocks out")
                assertTrue(toFlat >= o.walkLast - 0.01, "landed ${"%.1f".format(toFlat)} blocks from the spot")
            }
            assertEquals(Move.WALK, p.legs.last().move)
        }
    }

    @Test fun chainLimitHolds() {
        val p = plan(o = opts(soul = null).copy(maxChain = 2))
        var run = 0
        for (l in p.legs) { run = if (l.move == Move.WALK) 0 else run + 1; assertTrue(run <= 2, "${p.legs.map { it.move }}") }
    }

    @Test fun reaimBetweenEveryTwoTeleports() {
        for (o in listOf(opts(soul = null), opts(), opts(soul = null).copy(maxChain = 5))) {
            val steps = TravelMath.smooth(plan(o = o).legs.map { it.move to it.to })
            for ((a, b) in steps.zipWithNext())
                assertTrue(! (a.move != null && a.move != Move.WALK && b.move != null && b.move != Move.WALK), "two teleports with no re-aim: ${steps.map { it.move }}")
        }
    }

    @Test fun disabledEdgeIsAvoided() {
        val first = plan(o = opts(soul = null))
        val blink = first.legs.first { it.move == Move.BLINK }
        val key = TravelPlanner.edgeKey(blink.move, blink.from, blink.to)
        val again = plan(o = opts(soul = null).copy(disabled = setOf(key)))
        assertTrue(again.legs.none { TravelPlanner.edgeKey(it.move, it.from, it.to) == key })
    }

    @Test fun landingsAreLandableAndFinite() {
        val w = Field()
        repeat(40) { i -> w.extra += P(5 + i * 2 % 50, 1 + i % 3, (i % 13) - 6) }
        w.lavas += P(30, 1, 3)
        for (o in listOf(opts(), opts(soul = null))) {
            val p = TravelPlanner.plan(start, goal, w, w, o) ?: continue
            for (l in p.legs) {
                assertTrue(l.yaw.isFinite() && l.pitch.isFinite())
                if (l.move != Move.WALK) assertTrue(TravelMath.landable(l.to, w), "${l.move} to ${l.to}")
            }
        }
    }

    @Test fun overBudgetGivesUp() {
        var t = 0L
        val slow = opts(blink = false, ether = false).copy(budgetNanos = 8_000_000L, clock = { t += 10_000_000L; t })
        val p = assertNotNull(TravelPlanner.plan(start, goal, Field(), Field(), slow))
        assertTrue(p.overBudget && p.legs.isEmpty())
    }

    // ---- failures

    @Test fun failBookPolicy() {
        val b = TravelFail.Book()
        val a = P(0, 1, 0); val c = P(8, 1, 0)
        val miss1 = b.record(Move.BLINK, a, c, Reason.MISS)
        assertTrue(miss1.retry && miss1.errorScale < 1.0 && ! miss1.fallback && ! miss1.disabled)
        val miss2 = b.record(Move.BLINK, a, c, Reason.MISS)
        assertTrue(! miss2.retry && miss2.fallback && miss2.disabled, "second miss: walk and turn the edge off")
        assertTrue(TravelPlanner.edgeKey(Move.BLINK, a, c) in b.disabled)
        val rb = b.record(Move.ETHERWARP, a, P(50, 1, 0), Reason.RUBBERBAND)
        assertTrue(rb.disabled && rb.replan)
        assertTrue(b.record(Move.BLINK, c, a, Reason.MANA).fallback)
        assertTrue(b.record(Move.BLINK, c, a, Reason.COOLDOWN).retry)
        val l1 = b.record(Move.BLINK, c, P(16, 1, 0), Reason.LANDING); assertTrue(! l1.disabled && l1.fallback)
        val l2 = b.record(Move.BLINK, c, P(16, 1, 0), Reason.LANDING); assertTrue(l2.disabled)
        b.reset(); assertTrue(b.disabled.isEmpty())
        val line = TravelFail.line(Reason.MISS, a, Move.BLINK, 1, true)
        for (f in listOf("reason=MISS", "at=0,1,0", "edge=BLINK", "retries=1", "fallback=walk")) assertTrue(f in line, line)
    }
}
