package tracker

import dev.marceline.utils.tracker.TrackerBucket
import dev.marceline.utils.tracker.Uptime
import kotlin.test.Test
import kotlin.test.assertEquals

class TrackerDataTest {
    @Test
    fun `the clock runs only while the tracker is fed`() {
        // fed just now: the whole step counts
        assertEquals(50L, Uptime.step(lastTick = 1_000, now = 1_050, lastChange = 1_000))
        // nothing for longer than the pause: the step doesn't count
        assertEquals(0L, Uptime.step(lastTick = 40_000, now = 40_050, lastChange = 1_000))
        // never fed, first tick, time going backwards: nothing
        assertEquals(0L, Uptime.step(lastTick = 1_000, now = 1_050, lastChange = 0))
        assertEquals(0L, Uptime.step(lastTick = 0, now = 1_050, lastChange = 1_000))
        assertEquals(0L, Uptime.step(lastTick = 2_000, now = 1_050, lastChange = 1_000))
        // a lag spike counts at most a second
        assertEquals(1_000L, Uptime.step(lastTick = 1_000, now = 6_000, lastChange = 5_900))
    }

    @Test
    fun `per hour needs ten seconds`() {
        assertEquals(0.0, Uptime.perHour(1_000.0, 9_999))
        assertEquals(3_600.0, Uptime.perHour(10.0, 10_000))
        assertEquals(120_000.0, Uptime.perHour(60_000.0, 1_800_000))
    }

    @Test
    fun `items keep amount and times, counters add up`() {
        val b = TrackerBucket()
        b.add("MAGMA_FISH", 64); b.add("MAGMA_FISH", 3)
        b.count("catches"); b.count("catches", 4)
        assertEquals(67L, b.items["MAGMA_FISH"]!!.amount)
        assertEquals(2L, b.items["MAGMA_FISH"]!!.times)
        assertEquals(5L, b.counter("catches"))
        assertEquals(0L, b.counter("kills"))
    }
}
