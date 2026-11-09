package human

import dev.marceline.utils.AimPriority
import dev.marceline.utils.HumanMotion
import dev.marceline.utils.LeasedAimBroker
import dev.marceline.utils.TurnConfig
import dev.marceline.utils.TurnEngine
import dev.marceline.utils.Turner
import java.io.File
import java.util.Random
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AimBrokerTest {
    private val cfg = TurnConfig(snap = false, overshootMin = 0.3f, overshootMax = 0.5f)

    /** A game in miniature: a fake view, a turn engine on it, a tick clock and four frames per tick. */
    private class World(seed: Long, cfg: TurnConfig) {
        val view = FakeView()
        val engine = TurnEngine(Random(seed)) { cfg }
        var tick = 0L
        val turner = object: Turner {
            override fun turnTo(yaw: Float, pitch: Float, tolerance: Float, overshoot: Boolean, urgent: Boolean) = engine.turn(view, yaw, pitch, tolerance, overshoot, tick, ! urgent)
            override fun glide(yaw: Float, pitch: Float) = engine.glide(view, yaw, pitch, tick)
            override fun setView(yaw: Float, pitch: Float) = engine.write(view, yaw, pitch)
            override fun nudgeView(dYaw: Float, dPitch: Float) = engine.write(view, view.yaw + dYaw, view.pitch + dPitch)
            override fun resetTurnState() = engine.resetTurnState()
        }
        val broker = LeasedAimBroker(turner)
        val frames = ArrayList<Pair<Float, Float>>()

        /** Start of a tick (what Humanize.tick does), then [logic], then the frames. */
        fun step(logic: () -> Unit) {
            engine.settle(view)
            broker.tick()
            logic()
            for (f in 1 .. 4) {
                engine.frame(view, tick, f / 4f)
                frames += view.yaw to view.pitch
            }
            tick++
        }
    }

    // a route of targets with a pause longer than the lease in the middle
    private val script: List<Pair<Float, Float>?> =
        List(40) { 120f to 15f } + List(60) { null } + List(50) { - 70f to - 20f } + List(30) { 10f to 5f }

    @Test
    fun `golden - single owner, same seed, same path as the engine without the broker`() {
        // without the broker: Humanize as it was, one turn per tick
        val direct = World(11, cfg)
        for (t in script) direct.step { t?.let { direct.engine.turn(direct.view, it.first, it.second, 1.5f, true, direct.tick) } }
        // through the broker, one owner
        val brokered = World(11, cfg)
        val owner = Any()
        for (t in script) brokered.step { t?.let { brokered.broker.turn(owner, AimPriority.TRAVEL, it.first, it.second, 1.5f) } }
        assertEquals(direct.frames, brokered.frames)
    }

    @Test
    fun `two owners taking turns - nothing jumps between frames`() {
        val w = World(5, cfg)
        val a = Any(); val b = Any()
        var beforeLogic = 0f to 0f
        for (t in 0 until 200) {
            w.step {
                beforeLogic = w.view.yaw to w.view.pitch
                // A always tries; B comes in every other 20 ticks and gives the view back after
                w.broker.turn(a, AimPriority.TRAVEL, if (t % 40 < 20) 150f else - 150f, 10f, 1.5f)
                if (t % 40 in 10 .. 29) w.broker.turn(b, AimPriority.COMBAT, if (t % 80 < 40) - 60f else 80f, - 30f, 1.5f)
                if (t % 40 == 30) w.broker.release(b)
                // a tick's logic never moves the view itself: only the frames do
                assertEquals(beforeLogic, w.view.yaw to w.view.pitch, "the view jumped during tick $t's logic")
            }
        }
        var prev = 0f to 0f
        for ((i, f) in w.frames.withIndex()) {
            val d = abs(HumanMotion.wrapDeg(f.first - prev.first)) + abs(f.second - prev.second)
            assertTrue(d <= 25f, "frame $i moved $d degrees")
            prev = f
        }
    }

    @Test
    fun `a new owner's turn is not pushed back the other way by the old overshoot`() {
        val w = World(9, cfg)
        val a = Any(); val b = Any()
        repeat(6) { w.step { w.broker.turn(a, AimPriority.TRAVEL, 170f, 0f, 1.5f) } } // A is turning right, overshoot set
        assertTrue(w.engine.osYaw > 0f, "A's turn should have an overshoot to the right")
        val start = w.view.yaw
        w.step { w.broker.turn(b, AimPriority.COMBAT, start - 60f, 0f, 1.5f) } // B takes it and turns left
        assertTrue(w.engine.osYaw <= 0f, "B's overshoot points ${w.engine.osYaw}")
        repeat(3) { w.step { w.broker.turn(b, AimPriority.COMBAT, start - 60f, 0f, 1.5f) } }
        assertTrue(HumanMotion.wrapDeg(w.view.yaw - start) < 0f, "B went right: ${w.view.yaw} from $start")
    }

    @Test
    fun `a lower priority can't move the view once a higher one has it`() {
        val w = World(1, cfg)
        val low = Any(); val high = Any()
        assertTrue(w.broker.acquire(low, AimPriority.TRAVEL))
        assertTrue(w.broker.acquire(high, AimPriority.COMBAT))
        assertFalse(w.broker.turnTo(low, 10f, 0f, 1f))
        assertFalse(w.broker.acquire(low, AimPriority.TRAVEL))
        assertFalse(w.broker.acquire(low, AimPriority.COMBAT), "equal priority doesn't take it either")
        assertSame(high, w.broker.holder)
    }

    @Test
    fun `after the lease runs out nobody holds the view and turning does nothing`() {
        val w = World(2, cfg)
        val a = Any()
        w.step { w.broker.turn(a, AimPriority.TRAVEL, 90f, 0f, 1f) }
        repeat(w.broker.leaseTicks) { w.step { } }
        assertNull(w.broker.holder)
        val before = w.view.yaw to w.view.pitch
        assertFalse(w.broker.turnTo(a, 90f, 0f, 1f))
        w.step { }
        assertEquals(before, w.view.yaw to w.view.pitch)
    }

    @Test
    fun `the same owner taking the view again keeps its turn, a different owner starts clean`() {
        val w = World(3, cfg)
        val a = Any(); val b = Any()
        repeat(5) { w.step { w.broker.turn(a, AimPriority.TRAVEL, 100f, 20f, 1f) } }
        val vel = w.engine.vel; val ly = w.engine.lastYaw; val ts = w.engine.turnSpeed
        assertTrue(vel > 0f)
        assertTrue(w.broker.acquire(a, AimPriority.COMBAT))
        assertEquals(vel, w.engine.vel); assertEquals(ly, w.engine.lastYaw); assertEquals(ts, w.engine.turnSpeed)
        // a lease that ran out and the same owner back: still its turn (no reset, as without the broker)
        repeat(w.broker.leaseTicks) { w.step { } }
        assertTrue(w.broker.acquire(a, AimPriority.TRAVEL))
        assertEquals(ly, w.engine.lastYaw)
        // someone else: everything reset
        assertTrue(w.broker.acquire(b, AimPriority.CRITICAL))
        assertEquals(0f, w.engine.vel); assertEquals(0f, w.engine.lastYaw); assertEquals(0f, w.engine.osYaw); assertEquals(0f, w.engine.curveFrom)
    }

    @Test
    fun `many owners fighting - one holder, and only the holder moves the view`() {
        val w = World(4, cfg)
        val owners = List(5) { Any() }
        val rnd = Random(77)
        repeat(3000) { i ->
            w.step {
                repeat(3) {
                    val o = owners[rnd.nextInt(owners.size)]
                    val p = AimPriority.entries[rnd.nextInt(AimPriority.entries.size)]
                    when (rnd.nextInt(10)) {
                        0 -> w.broker.release(o)
                        in 1 .. 4 -> if (w.broker.acquire(o, p)) assertSame(o, w.broker.holder, "acquire true but someone else holds ($i)")
                        else -> {
                            val holder = w.broker.holder
                            val moved = w.broker.turnTo(o, rnd.nextFloat() * 360f - 180f, rnd.nextFloat() * 120f - 60f, 1f)
                            if (holder !== o) assertFalse(moved, "a non-holder turned ($i)")
                        }
                    }
                    assertTrue(w.broker.holder == null || owners.any { it === w.broker.holder })
                }
            }
        }
    }

    @Test
    fun `features look only through the broker`() {
        // every automatic look goes through Aim; Humanize's own moves are called from Humanize and the broker only
        val rel = "src/main/kotlin/dev/marceline"
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && ! File(d, rel).isDirectory) d = d.parentFile
        val src = File(d ?: error("sources not found from ${System.getProperty("user.dir")}"), rel)
        assertTrue(src.walkTopDown().any { it.name == "AutoFishing.kt" }, "scanned the wrong folder: $src")
        val direct = Regex("""Humanize\.(turnTo|glide|setView|nudgeView|lookAt)\(""")
        val offenders = src.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.name != "Humanize.kt" && it.name != "AimBroker.kt" }
            .flatMap { f -> f.readLines().mapIndexedNotNull { n, l -> if (direct.containsMatchIn(l)) "${f.path}:${n + 1}" else null } }
            .toList()
        assertTrue(offenders.isEmpty(), "looks that skip the broker: $offenders")
    }
}
