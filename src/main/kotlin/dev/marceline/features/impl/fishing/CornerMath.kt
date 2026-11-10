package dev.marceline.features.impl.fishing

/**
 * Picking a corner to hold against a big sea creature (Thunder, Lord Jawbus): two walls at right angles behind
 * you, body-high, so a knock can only throw you along the open sides - and no lava close along those. Pure, so
 * it's tested; the world is read by the caller.
 */
object CornerMath {
    enum class Dir(val dx: Int, val dz: Int) { N(0, - 1), E(1, 0), S(0, 1), W(- 1, 0) }

    /**
     * One standable block. [walls]: sides with a solid block at feet and head height. [lavaRun]: for each open
     * side, how many blocks until lava (at the feet or the floor below), null when none within the look.
     */
    data class Spot(val x: Int, val y: Int, val z: Int, val walls: Set<Dir>, val lavaRun: Map<Dir, Int?>, val dist: Double)

    /** A corner: a wall on the north or south side and one on the east or west side. */
    fun isCorner(walls: Set<Dir>) = (Dir.N in walls || Dir.S in walls) && (Dir.E in walls || Dir.W in walls)

    /** Higher is better; null when it isn't a corner or lava is within [minLavaGap] along an open side. */
    fun score(s: Spot, minLavaGap: Int = 4): Double? {
        if (! isCorner(s.walls)) return null
        val open = Dir.entries.filter { it !in s.walls }
        if (open.any { (s.lavaRun[it] ?: Int.MAX_VALUE) < minLavaGap }) return null
        val lavaRoom = open.sumOf { minOf(s.lavaRun[it] ?: 8, 8) }
        return s.walls.size * 3.0 + lavaRoom * 0.3 - s.dist * 0.6
    }

    /** Best spots first (only the ones that qualify). */
    fun rank(spots: List<Spot>, minLavaGap: Int = 4): List<Spot> =
        spots.mapNotNull { s -> score(s, minLavaGap)?.let { s to it } }.sortedByDescending { it.second }.map { it.first }
}
