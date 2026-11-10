package dev.marceline.features.impl.fishing

/**
 * Where a knocked-back body goes: the game's air physics per tick (move, then gravity 0.08 and drag 0.98 on the
 * way down, horizontal drag 0.91 in the air). Pure, so it's tested; the lava guard asks it whether a knock is
 * carrying us over lava before we get there.
 */
object LavaGuardMath {
    const val GRAVITY = 0.08
    const val DRAG_Y = 0.98
    const val DRAG_AIR = 0.91

    /** Horizontal speed (blocks / tick) above which a move is a knock, not our own walking (sprinting is ~0.28). */
    const val KNOCK_SPEED = 0.36

    /** The feet positions over the next [ticks] ticks, starting from the next one. */
    fun path(x: Double, y: Double, z: Double, vx: Double, vy: Double, vz: Double, ticks: Int = 20): List<DoubleArray> {
        var px = x; var py = y; var pz = z
        var mx = vx; var my = vy; var mz = vz
        val out = ArrayList<DoubleArray>(ticks)
        repeat(ticks) {
            px += mx; py += my; pz += mz
            out += doubleArrayOf(px, py, pz)
            my = (my - GRAVITY) * DRAG_Y
            mx *= DRAG_AIR; mz *= DRAG_AIR
        }
        return out
    }

    fun isKnock(vx: Double, vz: Double) = vx * vx + vz * vz > KNOCK_SPEED * KNOCK_SPEED

    /**
     * Follows [path] until it meets ground ([solid] under the feet) or lava ([lava] at / under the feet):
     * the tick it lands in lava, or null when it lands safely (or never lands within the path).
     */
    fun landsInLava(path: List<DoubleArray>, solid: (Int, Int, Int) -> Boolean, lava: (Int, Int, Int) -> Boolean): Int? {
        for ((i, p) in path.withIndex()) {
            val bx = kotlin.math.floor(p[0]).toInt(); val by = kotlin.math.floor(p[1]).toInt(); val bz = kotlin.math.floor(p[2]).toInt()
            if (lava(bx, by, bz)) return i
            if (solid(bx, by - 1, bz) && p[1] - by < 0.2) return null // standing on something
            if (lava(bx, by - 1, bz) && p[1] - by < 0.2) return i
        }
        return null
    }
}
