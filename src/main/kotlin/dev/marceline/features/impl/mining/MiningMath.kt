package dev.marceline.features.impl.mining

import dev.marceline.utils.HumanProfile
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * The pure maths of mining, with no game objects, so it can be unit tested:
 * which blocks count for a mode, which target to take next, and where on the block to aim.
 */
object MiningMath {
    /** One kind of block a mode mines, by its Minecraft block id, with how much we want it. */
    data class Kind(val name: String, val ids: Set<String>, val priority: Int)

    /** Mithril in the Dwarven Mines: grey wool < prismarine < light blue wool; titanium (polished diorite) on top. */
    val MITHRIL_GREY = Kind("Grey Mithril", setOf("gray_wool"), 1)
    val MITHRIL_GREEN = Kind("Green Mithril", setOf("prismarine", "prismarine_bricks", "dark_prismarine"), 2)
    val MITHRIL_BLUE = Kind("Blue Mithril", setOf("light_blue_wool"), 3)
    val TITANIUM = Kind("Titanium", setOf("polished_diorite"), 4)

    /** Gemstones are stained glass blocks and panes; the colour names the gem. */
    val GEMSTONES: List<Kind> = listOf(
        "Ruby" to "red", "Amber" to "orange", "Sapphire" to "light_blue", "Jade" to "lime",
        "Amethyst" to "purple", "Topaz" to "yellow", "Jasper" to "magenta", "Opal" to "white",
        "Aquamarine" to "blue", "Citrine" to "brown", "Onyx" to "black", "Peridot" to "green",
    ).map { (gem, colour) -> Kind(gem, setOf("${colour}_stained_glass", "${colour}_stained_glass_pane"), 2) }

    /** A block we could mine now: how far it is, how far the view has to turn, and how much we want it. */
    data class Candidate(val key: Long, val distance: Double, val turnDegrees: Double, val priority: Int)

    /**
     * Lower is better. Wanted kinds win first, then a short turn, then a short reach.
     * A block of a higher priority is worth about 40 degrees of turning.
     */
    fun score(c: Candidate): Double = c.turnDegrees + c.distance * 6.0 - c.priority * 40.0

    /**
     * The next target. Usually the best score; with [HumanProfile.failRate] a person's "good enough" pick
     * (the second or third best) instead. Null when there is nothing.
     */
    fun choose(cands: List<Candidate>, profile: HumanProfile, rnd: Random): Candidate? {
        if (cands.isEmpty()) return null
        val sorted = cands.sortedBy(::score)
        if (sorted.size == 1 || profile.failRate <= 0.0 || rnd.nextDouble() >= profile.failRate) return sorted[0]
        return sorted[1 + rnd.nextInt(minOf(2, sorted.size - 1))]
    }

    /** Degrees between where we look (yaw, pitch) and where [dx], [dy], [dz] (from the eye) is. */
    fun turnDegrees(yaw: Float, pitch: Float, dx: Double, dy: Double, dz: Double): Double {
        val h = sqrt(dx * dx + dz * dz)
        val ty = Math.toDegrees(atan2(- dx, dz))
        val tp = - Math.toDegrees(atan2(dy, h))
        var d = (ty - yaw) % 360.0
        if (d > 180) d -= 360.0
        if (d < - 180) d += 360.0
        val p = tp - pitch
        return sqrt(d * d + p * p)
    }

    /**
     * A point to aim at on the face of block (bx, by, bz) that looks towards the eye, moved within the face
     * by [u] and [v] (each -1..1, scaled to stay [margin] away from the edges). Returns x, y, z.
     */
    fun facePoint(ex: Double, ey: Double, ez: Double, bx: Int, by: Int, bz: Int, u: Double, v: Double, margin: Double = 0.18): DoubleArray {
        val cx = bx + 0.5; val cy = by + 0.5; val cz = bz + 0.5
        val dx = ex - cx; val dy = ey - cy; val dz = ez - cz
        val span = 0.5 - margin
        val su = u.coerceIn(- 1.0, 1.0) * span
        val sv = v.coerceIn(- 1.0, 1.0) * span
        return when {
            abs(dx) >= abs(dy) && abs(dx) >= abs(dz) -> doubleArrayOf(cx + 0.5 * sign(dx), cy + sv, cz + su)
            abs(dy) >= abs(dz) -> doubleArrayOf(cx + su, cy + 0.5 * sign(dy), cz + sv)
            else -> doubleArrayOf(cx + su, cy + sv, cz + 0.5 * sign(dz))
        }
    }

    private fun sign(d: Double) = if (d < 0) - 1.0 else 1.0

    /**
     * How long to keep at one block before skipping it: three times the usual break time of that kind
     * (median of [samples], in ticks), never under [floorTicks] (the setting) nor over [capTicks].
     * Without five samples yet, just the floor.
     */
    fun giveUpTicks(samples: List<Int>, floorTicks: Int, capTicks: Int): Int {
        if (samples.size < 5) return floorTicks
        val med = samples.sorted()[samples.size / 2]
        return (med * 3).coerceIn(floorTicks, maxOf(floorTicks, capTicks))
    }

    /** Middle value of [samples] (ticks), or -1 when empty. */
    fun median(samples: List<Int>): Int = if (samples.isEmpty()) - 1 else samples.sorted()[samples.size / 2]

    /**
     * True when the point (px, py, pz) lies on someone's line of sight: within [maxDist] in front of their eye
     * and within [radius] of the ray (look vector lx, ly, lz, any length). Used to leave a block to a player who
     * is already mining it.
     */
    fun onRay(ex: Double, ey: Double, ez: Double, lx: Double, ly: Double, lz: Double, px: Double, py: Double, pz: Double, maxDist: Double, radius: Double): Boolean {
        val len = sqrt(lx * lx + ly * ly + lz * lz)
        if (len < 1e-9) return false
        val ux = lx / len; val uy = ly / len; val uz = lz / len
        val dx = px - ex; val dy = py - ey; val dz = pz - ez
        val along = dx * ux + dy * uy + dz * uz
        if (along < 0 || along > maxDist) return false
        val cx = dx - ux * along; val cy = dy - uy * along; val cz = dz - uz * along
        return cx * cx + cy * cy + cz * cz <= radius * radius
    }

    /** Blocks per hour, or 0 when too little time has passed to say. */
    fun perHour(count: Int, millis: Long): Long = if (millis < 60_000) 0 else (count * 3_600_000.0 / millis).toLong()
}
