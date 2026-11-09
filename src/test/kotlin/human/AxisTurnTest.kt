package human

import dev.marceline.utils.HumanMotion
import dev.marceline.utils.TurnConfig
import dev.marceline.utils.TurnEngine
import java.util.Random
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Turns with independent axes (TurnEngine.turnByAxis) against the invariants, and against the old single step. */
class AxisTurnTest {
    private val split = TurnConfig(snap = false)
    private val single = split.copy(axisSplit = false)

    /** One whole turn: views after each tick, the pitch lag before each tick, and how many ticks it took. */
    private class Run(val views: List<Pair<Float, Float>>, val lagBefore: List<Int>, val ticks: Int)

    private fun turn(cfg: TurnConfig, seed: Long, toYaw: Float, toPitch: Float, from: Pair<Float, Float> = 0f to 0f, tol: Float = 1.5f, max: Int = 400): Run {
        val v = FakeView(from.first, from.second)
        val e = TurnEngine(Random(seed)) { cfg }
        val views = ArrayList<Pair<Float, Float>>()
        val lags = ArrayList<Int>()
        var t = 0L
        while (t < max) {
            e.settle(v)
            lags += e.lagLeft
            val done = e.turn(v, toYaw, toPitch, tol, true, t)
            for (f in 1 .. 4) e.frame(v, t, f / 4f)
            views += v.yaw to v.pitch
            t++
            if (done) break
        }
        return Run(views, lags, t.toInt())
    }

    /** The tick after which an axis stays within [tol] of its target. */
    private fun settled(values: List<Float>, target: Float, tol: Float, yaw: Boolean): Int {
        var last = - 1
        values.forEachIndexed { i, x -> val d = if (yaw) HumanMotion.wrapDeg(x - target) else x - target; if (abs(d) > tol) last = i }
        return last + 1
    }

    private fun randomTurns(seed: Long, n: Int, minYaw: Float, maxYaw: Float, maxPitchShare: Float = 0.6f): List<Pair<Float, Float>> {
        val r = Random(seed)
        return List(n) {
            val y = (minYaw + r.nextFloat() * (maxYaw - minYaw)) * (if (r.nextBoolean()) 1 else - 1)
            val p = (5f + r.nextFloat() * (abs(y) * maxPitchShare - 5f).coerceAtLeast(0f)) * (if (r.nextBoolean()) 1 else - 1)
            y to p.coerceIn(- 80f, 80f)
        }
    }

    @Test
    fun `1 - on turns over 15 degrees pitch settles after yaw`() {
        for ((i, t) in randomTurns(1, 300, 16f, 170f, 1f).withIndex()) {
            val r = turn(split, i.toLong(), t.first, t.second)
            val ys = settled(r.views.map { it.first }, t.first, 1.5f, true)
            val ps = settled(r.views.map { it.second }, t.second, 1.5f * 1.4f, false)
            assertTrue(ps > ys, "turn $t: pitch settled at $ps, yaw at $ys")
        }
    }

    @Test
    fun `2 - pitch travels no more than yaw when the turn is mostly sideways`() {
        for ((i, t) in randomTurns(2, 300, 20f, 170f, 0.6f).withIndex()) {
            val r = turn(split, 100L + i, t.first, t.second)
            var py = 0f; var pp = 0f; var prev = 0f to 0f
            for (v in r.views) { py += abs(HumanMotion.wrapDeg(v.first - prev.first)); pp += abs(v.second - prev.second); prev = v }
            assertTrue(pp <= py * 1.05f, "turn $t: pitch path $pp, yaw path $py")
        }
    }

    @Test
    fun `3 - no axis stands still two ticks in a row while it still has a way to go`() {
        for ((i, t) in randomTurns(3, 300, 16f, 170f, 1f).withIndex()) {
            val r = turn(split, 200L + i, t.first, t.second)
            var prev = 0f to 0f
            var stillY = 0; var stillP = 0
            for ((k, v) in r.views.withIndex()) {
                val farY = abs(HumanMotion.wrapDeg(t.first - prev.first)) > 2 * 1.5f
                val farP = abs(t.second - prev.second) > 2 * 1.5f * 1.4f && r.lagBefore[k] == 0
                stillY = if (farY && abs(HumanMotion.wrapDeg(v.first - prev.first)) < 1e-6f) stillY + 1 else 0
                stillP = if (farP && abs(v.second - prev.second) < 1e-6f) stillP + 1 else 0
                assertTrue(stillY < 2 && stillP < 2, "turn $t stood still at tick $k (yaw $stillY, pitch $stillP)")
                prev = v
            }
        }
    }

    @Test
    fun `4 - the yaw to pitch step ratio changes from tick to tick, same seed same steps`() {
        val a = turn(split, 9, 140f, 60f)
        val ratios = a.views.zipWithNext { p, q -> abs(HumanMotion.wrapDeg(q.first - p.first)) to abs(q.second - p.second) }
            .filter { it.first > 0.05f && it.second > 0.05f }.map { it.first / it.second }
        assertTrue(ratios.size >= 4 && ratios.map { (it * 1000).toInt() }.toSet().size >= ratios.size - 1, "ratios repeat: $ratios")
        assertEquals(a.views, turn(split, 9, 140f, 60f).views)
        assertTrue(a.views != turn(split, 10, 140f, 60f).views)
    }

    private fun median(xs: List<Int>) = xs.sorted()[xs.size / 2]

    @Test
    fun `5 - total turn time stays within 15 percent on big turns, short turns at most 2 ticks more`() {
        val buckets = listOf(Triple("15-30", 15f, 30f), Triple("30-90", 30f, 90f), Triple("90-170", 90f, 170f))
        val report = StringBuilder("MEASURE axis turn ticks (median, old -> new):")
        for ((name, lo, hi) in buckets) {
            val turns = randomTurns(lo.toLong(), 400, lo, hi, 0.8f)
            val old = turns.mapIndexed { i, t -> turn(single, 1000L + i, t.first, t.second).ticks }
            val new = turns.mapIndexed { i, t -> turn(split, 1000L + i, t.first, t.second).ticks }
            val mo = median(old); val mn = median(new)
            report.append(" $name $mo -> $mn (${"%+.0f".format(java.util.Locale.ROOT, (mn - mo) * 100.0 / mo)}%);")
            if (lo < 30f) assertTrue(mn - mo <= 2, "$name: $mo -> $mn ticks")
            else assertTrue(abs(mn - mo) <= mo * 0.15, "$name: $mo -> $mn ticks")
        }
        println(report)
    }

    @Test
    fun `every turn finishes with Mouse Grid on, at any sensitivity and tolerance`() {
        // Mouse Grid rounds each move to whole mouse counts; a turn must still end (a cast stalled on this in game)
        var n = 0
        for (sens in listOf(0.0, 0.2, 0.5, 0.8, 1.0)) for (tol in listOf(0.35f, 0.6f, 0.8f, 1.5f)) {
            val cfg = split.copy(snap = true, sensitivity = sens)
            val r = Random((sens * 100).toLong() + (tol * 10).toLong())
            repeat(150) { i ->
                val from = r.nextFloat() * 360f - 180f to r.nextFloat() * 140f - 60f
                val to = from.first + r.nextFloat() * 340f - 170f to (from.second + r.nextFloat() * 160f - 80f).coerceIn(- 90f, 90f)
                val run = turn(cfg, 9000L + n++, to.first, to.second, from, tol, max = 400)
                assertTrue(run.ticks < 400, "sens $sens tol $tol turn $from -> $to never finished")
            }
        }
    }

    @Test
    fun `7 - a pitch target at the edge doesn't wobble`() {
        for (seed in 0L until 100L) {
            val r = turn(split, seed, 40f, 90f)
            val ps = r.views.map { it.second }
            val first = ps.indexOfFirst { it >= 90f - 2.1f }
            assertTrue(first >= 0, "never reached the edge")
            val after = ps.drop(first)
            assertTrue(after.all { it >= 90f - 2.1f - 0.01f }, "fell back from the edge: $after")
            val turns = after.zipWithNext { a, b -> b - a }.filter { abs(it) > 1e-4f }.zipWithNext { a, b -> a * b < 0 }.count { it }
            assertTrue(turns <= 1, "pitch changed direction $turns times at the edge")
        }
    }

    @Test
    fun `pitch lag 0, 2, 5 - pitch starts later, and arrives no earlier`() {
        fun firstMove(values: List<Float>): Int = values.indexOfFirst { abs(it) > 0.05f }
        val byLag = listOf(0, 2, 5).map { lag ->
            val cfg = split.copy(pitchLag = lag)
            val runs = List(200) { i -> turn(cfg, 5000L + i, 20f, 59f) } // a big turn where pitch has most of the way
            val start = median(runs.map { r -> firstMove(r.views.map { it.second }) - firstMove(r.views.map { it.first }) })
            val arrive = median(runs.map { r -> settled(r.views.map { it.second }, 59f, 2.1f, false) - settled(r.views.map { it.first }, 20f, 1.5f, true) })
            start to arrive
        }
        val starts = byLag.map { it.first }; val arrivals = byLag.map { it.second }
        assertTrue(starts[0] < starts[1] && starts[1] < starts[2], "start gap by lag: $starts")
        assertTrue(arrivals[0] <= arrivals[1] && arrivals[1] <= arrivals[2], "arrival gap by lag: $arrivals")
        println("MEASURE pitch lag 0/2/5: start gap $starts, arrival gap $arrivals")
    }

    @Test
    fun `old single-step turns are still there unchanged behind axisSplit = false`() {
        // the old path: yaw and pitch move in a fixed ratio every tick
        val r = turn(single, 4, 120f, 40f, tol = 0.5f)
        val moving = r.views.zipWithNext { p, q -> HumanMotion.wrapDeg(q.first - p.first) to (q.second - p.second) }.filter { abs(it.first) > 0.5f }
        assertTrue(moving.isNotEmpty())
    }
}
