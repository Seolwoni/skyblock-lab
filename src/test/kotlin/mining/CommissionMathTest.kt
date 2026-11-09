package mining

import dev.marceline.features.impl.mining.CommissionMath
import dev.marceline.features.impl.mining.CommissionMath.Commission
import dev.marceline.features.impl.mining.CommissionMath.Job
import dev.marceline.features.impl.mining.CommissionMath.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommissionMathTest {
    private val tab = listOf(
        "Area: Dwarven Mines",
        "Server: mini12A",
        "",
        "Commissions:",
        " Royal Mines Mithril: 45%",
        " Goblin Slayer: 10%",
        " Titanium Miner: DONE",
        " Mithril Miner: 87.5%",
        "",
        "Powders:",
        " Mithril: 120,000",
    )

    @Test
    fun `reads the commissions and stops at the next heading`() {
        val c = CommissionMath.parse(tab)
        assertEquals(listOf("Royal Mines Mithril", "Goblin Slayer", "Titanium Miner", "Mithril Miner"), c.map { it.name })
        assertEquals(0.45, c[0].progress, 1e-9)
        assertTrue(c[2].done)
        assertEquals(0.875, c[3].progress, 1e-9)
    }

    @Test
    fun `no commissions widget - empty`() = assertTrue(CommissionMath.parse(listOf("Area: Hub", "Skills: 1")).isEmpty())

    @Test
    fun `headings without a blank line in between still end the list`() {
        val c = CommissionMath.parse(listOf("Commissions:", " Mithril Miner: 5%", "Powders:", " Mithril: 3"))
        assertEquals(1, c.size)
    }

    @Test
    fun `tasks - job and area`() {
        assertEquals(CommissionMath.Task(Job.MITHRIL, null), CommissionMath.taskOf("Mithril Miner"))
        assertEquals(CommissionMath.Task(Job.TITANIUM, null), CommissionMath.taskOf("Titanium Miner"))
        assertEquals(CommissionMath.Task(Job.MITHRIL, "Royal Mines"), CommissionMath.taskOf("Royal Mines Mithril"))
        assertEquals(CommissionMath.Task(Job.TITANIUM, "Rampart's Quarry"), CommissionMath.taskOf("Rampart's Quarry Titanium"))
        assertEquals(Job.UNSUPPORTED, CommissionMath.taskOf("Goblin Slayer").job)
        assertEquals(Job.UNSUPPORTED, CommissionMath.taskOf("2x Mithril Powder Collector").job)
    }

    @Test
    fun `next - closest to done that we can do, area ones only with a route`() {
        val c = CommissionMath.parse(tab)
        assertEquals("Mithril Miner", CommissionMath.pickNext(c, setOf("royal mines"), null)!!.name)
        val noAny = c.filter { it.name != "Mithril Miner" }
        assertEquals("Royal Mines Mithril", CommissionMath.pickNext(noAny, setOf("Royal Mines"), null)!!.name)
        assertNull(CommissionMath.pickNext(noAny, emptySet(), null), "no route to Royal Mines, the rest done or unsupported")
    }

    @Test
    fun `ties prefer the area we are in`() {
        val c = listOf(Commission("Lava Springs Mithril", 0.5, false), Commission("Royal Mines Mithril", 0.5, false))
        assertEquals("Royal Mines Mithril", CommissionMath.pickNext(c, setOf("Lava Springs", "Royal Mines"), "Royal Mines")!!.name)
    }

    @Test
    fun `area for a task`() {
        val routes = listOf("Lava Springs", "Royal Mines")
        assertEquals("Royal Mines", CommissionMath.areaFor(CommissionMath.Task(Job.MITHRIL, "royal mines"), routes, null))
        assertEquals("Lava Springs", CommissionMath.areaFor(CommissionMath.Task(Job.MITHRIL, null), routes, "Lava Springs"))
        assertEquals("Lava Springs", CommissionMath.areaFor(CommissionMath.Task(Job.MITHRIL, null), routes, "Upper Mines"))
        assertNull(CommissionMath.areaFor(CommissionMath.Task(Job.MITHRIL, "Cliffside Veins"), routes, null))
    }

    @Test
    fun `routes round-trip through text and record every few blocks or a step`() {
        val r = listOf(Point(1.0, 64.0, 2.5), Point(-3.25, 65.0, 7.0))
        assertEquals(r, CommissionMath.parseRoute(CommissionMath.format(r)))
        assertTrue(CommissionMath.parseRoute("junk;1,2").isEmpty())
        assertTrue(CommissionMath.shouldRecord(null, Point(0.0, 0.0, 0.0)))
        assertTrue(! CommissionMath.shouldRecord(Point(0.0, 64.0, 0.0), Point(1.0, 64.0, 1.0)))
        assertTrue(CommissionMath.shouldRecord(Point(0.0, 64.0, 0.0), Point(3.0, 64.0, 0.0)))
        assertTrue(CommissionMath.shouldRecord(Point(0.0, 64.0, 0.0), Point(0.5, 65.0, 0.0)))
    }

    @Test
    fun `join a route at the nearest point`() {
        val r = listOf(Point(0.0, 0.0, 0.0), Point(10.0, 0.0, 0.0), Point(20.0, 0.0, 0.0))
        assertEquals(1, CommissionMath.joinAt(r, Point(11.0, 0.0, 1.0)))
    }
}
