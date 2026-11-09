package dev.marceline.features.impl.mining

import kotlin.math.sqrt

/**
 * The pure side of commissions, with no game objects: reading them from the tab list, telling which ones
 * Auto Mining can do and where, picking the next, and the walking routes (recorded points) as text.
 */
object CommissionMath {
    data class Commission(val name: String, val progress: Double, val done: Boolean)

    enum class Job { MITHRIL, TITANIUM, UNSUPPORTED }

    /** What a commission asks: the block job and, for "<Area> Mithril" ones, the area. */
    data class Task(val job: Job, val area: String?)

    private val LINE = Regex("^\\s*(.+?):\\s*(DONE|[0-9]+(?:\\.[0-9]+)?%)\\s*$", RegexOption.IGNORE_CASE)
    private val MINING = Regex("^(?:(.+?) )?(Mithril|Titanium)(?: Miner)?$", RegexOption.IGNORE_CASE)

    /** The commissions under the "Commissions:" heading of the tab list (colour codes already removed). */
    fun parse(lines: List<String>): List<Commission> {
        val start = lines.indexOfFirst { it.trim().equals("Commissions:", ignoreCase = true) }
        if (start < 0) return emptyList()
        val out = ArrayList<Commission>()
        for (raw in lines.drop(start + 1)) {
            val l = raw.trim()
            if (l.isEmpty()) break
            val m = LINE.find(l) ?: break // the next heading ("Powders:", "Skills: ..." ) ends the list
            val v = m.groupValues[2]
            val done = v.equals("DONE", ignoreCase = true)
            out += Commission(m.groupValues[1].trim(), if (done) 1.0 else v.removeSuffix("%").toDouble() / 100.0, done)
        }
        return out
    }

    fun taskOf(name: String): Task {
        val m = MINING.find(name.trim()) ?: return Task(Job.UNSUPPORTED, null)
        val area = m.groupValues[1].trim().ifEmpty { null }
        val job = if (m.groupValues[2].equals("Titanium", ignoreCase = true)) Job.TITANIUM else Job.MITHRIL
        return Task(job, area)
    }

    /**
     * The commission to do next: not done, one we can do, and if it names an area we need a route there
     * ([routes], area names, case ignored). Any-area ones are fine anywhere. The closest to done goes first;
     * ties prefer the area we are already in ([here]).
     */
    fun pickNext(list: List<Commission>, routes: Set<String>, here: String?): Commission? {
        val known = routes.map { it.lowercase() }.toSet()
        return list.filter { c ->
            if (c.done) return@filter false
            val t = taskOf(c.name)
            t.job != Job.UNSUPPORTED && (t.area == null || t.area.lowercase() in known)
        }.sortedWith(compareByDescending<Commission> { it.progress }.thenByDescending { taskOf(it.name).area.equals(here, ignoreCase = true) }).firstOrNull()
    }

    /** Where to mine for [task]: its own area, or for any-area ones the area we're in if we have a route, else the first route. */
    fun areaFor(task: Task, routes: List<String>, here: String?): String? = when {
        task.area != null -> routes.firstOrNull { it.equals(task.area, ignoreCase = true) }
        here != null && routes.any { it.equals(here, ignoreCase = true) } -> routes.first { it.equals(here, ignoreCase = true) }
        else -> routes.firstOrNull()
    }

    // ------------------------------------------------------------------ routes

    data class Point(val x: Double, val y: Double, val z: Double) {
        fun dist(o: Point): Double { val dx = x - o.x; val dy = y - o.y; val dz = z - o.z; return sqrt(dx * dx + dy * dy + dz * dz) }
        fun flat(o: Point): Double { val dx = x - o.x; val dz = z - o.z; return sqrt(dx * dx + dz * dz) }
    }

    /** True when a recording should take a new point: [step] blocks from the last one, or a step up / down. */
    fun shouldRecord(last: Point?, now: Point, step: Double = 3.0): Boolean =
        last == null || last.flat(now) >= step || kotlin.math.abs(now.y - last.y) >= 1.0

    fun format(route: List<Point>): String = route.joinToString(";") { "%.2f,%.2f,%.2f".format(java.util.Locale.ROOT, it.x, it.y, it.z) }

    fun parseRoute(s: String): List<Point> = s.split(';').mapNotNull { part ->
        val n = part.split(',').mapNotNull { it.trim().toDoubleOrNull() }
        if (n.size == 3) Point(n[0], n[1], n[2]) else null
    }

    /** Index of the route point to walk to first: the nearest one, then onwards (joining a route half way). */
    fun joinAt(route: List<Point>, at: Point): Int = route.indices.minByOrNull { route[it].dist(at) } ?: 0
}
