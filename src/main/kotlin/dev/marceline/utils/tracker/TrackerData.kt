package dev.marceline.utils.tracker

/*
 * Profit / count trackers, after SkyHanni's tracker framework (SkyHanniTracker, SkyHanniItemTracker, SessionUptime;
 * https://github.com/hannibal002/SkyHanni, LGPL-2.1, see LICENSE-SKYHANNI.txt). Rewritten for Marceline: plain data,
 * one store, buckets for this session / today / all time.
 */

/** How many of an item, and how many separate times it came in. */
class TrackedItem(
    var amount: Long = 0,
    var times: Long = 0,
    var hidden: Boolean = false,
)

/** One tracker's numbers over one span of time. */
class TrackerBucket(
    val items: HashMap<String, TrackedItem> = HashMap(),
    /** Named counts besides items: catches, kills, chests, coins picked up... */
    val counters: HashMap<String, Long> = HashMap(),
    /** Time spent actively doing the thing (see [Uptime]). */
    var uptimeMs: Long = 0,
) {
    fun add(id: String, amount: Long) {
        val t = items.getOrPut(id) { TrackedItem() }
        t.amount += amount
        t.times++
    }

    fun count(name: String, by: Long = 1) { counters[name] = (counters[name] ?: 0L) + by }
    fun counter(name: String) = counters[name] ?: 0L
    fun isEmpty() = items.isEmpty() && counters.isEmpty()
}

/** Saved part: all time, and one bucket per day (yyyy-MM-dd), per tracker id. */
class TrackerSave(
    val total: HashMap<String, TrackerBucket> = HashMap(),
    val days: HashMap<String, HashMap<String, TrackerBucket>> = HashMap(),
)

/** Which span a tracker shows. */
enum class TrackerSpan(val title: String) { SESSION("Session"), TODAY("Today"), TOTAL("Total") }

/**
 * The time actually spent: it runs only while the tracker keeps being fed. A gap longer than [pauseAfterMs] (or the
 * game paused) stops the clock, so per-hour numbers aren't diluted by standing around. Pure, for tests.
 */
object Uptime {
    const val DEFAULT_PAUSE_MS = 30_000L

    /** How much of the [now] - [lastTick] step counts, given the last time the tracker changed. */
    fun step(lastTick: Long, now: Long, lastChange: Long, pauseAfterMs: Long = DEFAULT_PAUSE_MS): Long {
        if (lastTick <= 0L || now <= lastTick) return 0L
        if (lastChange <= 0L || now - lastChange > pauseAfterMs) return 0L
        return (now - lastTick).coerceAtMost(1_000L) // never more than a second at a time (a hitch, a lag spike)
    }

    /** Per hour from a total and the time it took (0 under 10 s, too short to say). */
    fun perHour(total: Double, uptimeMs: Long): Double = if (uptimeMs < 10_000L) 0.0 else total * 3_600_000.0 / uptimeMs
}
