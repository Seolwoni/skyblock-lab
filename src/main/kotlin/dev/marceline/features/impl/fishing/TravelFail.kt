package dev.marceline.features.impl.fishing

import dev.marceline.features.impl.fishing.TravelMath.Move
import dev.marceline.utils.PathMath.P

/**
 * What went wrong with a teleport and what to do about it (pure). An edge that fails twice is turned off for the
 * session; a miss is tried once more with less aim error, then walked.
 */
object TravelFail {
    enum class Reason { MANA, SOULFLOW, LANDING, MISS, RUBBERBAND, COOLDOWN }

    /** What to do next: try again (with [errorScale] times the aim error), walk this stretch, re-plan. */
    data class Outcome(val retry: Boolean, val errorScale: Double, val fallback: Boolean, val replan: Boolean, val disabled: Boolean)

    class Book {
        private val fails = HashMap<String, Int>()
        private val misses = HashMap<String, Int>()
        val disabled = LinkedHashSet<String>()

        fun record(move: Move, from: P, to: P, reason: Reason): Outcome {
            val key = TravelPlanner.edgeKey(move, from, to)
            fun disableIf(n: Int) = (n >= 2).also { if (it) disabled += key }
            return when (reason) {
                Reason.COOLDOWN -> Outcome(retry = true, errorScale = 1.0, fallback = false, replan = false, disabled = false)
                Reason.MANA, Reason.SOULFLOW -> Outcome(retry = false, errorScale = 1.0, fallback = true, replan = true, disabled = false)
                Reason.RUBBERBAND -> { disabled += key; fails[key] = (fails[key] ?: 0) + 1; Outcome(false, 1.0, fallback = false, replan = true, disabled = true) }
                Reason.LANDING -> {
                    val n = (fails[key] ?: 0) + 1; fails[key] = n
                    Outcome(retry = false, errorScale = 1.0, fallback = true, replan = true, disabled = disableIf(n))
                }
                Reason.MISS -> {
                    val miss = (misses[key] ?: 0) + 1; misses[key] = miss
                    val n = (fails[key] ?: 0) + 1; fails[key] = n
                    if (miss == 1) Outcome(retry = true, errorScale = 0.5, fallback = false, replan = false, disabled = disableIf(n))
                    else Outcome(retry = false, errorScale = 1.0, fallback = true, replan = true, disabled = disableIf(n))
                }
            }
        }

        fun reset() { fails.clear(); misses.clear(); disabled.clear() }
    }

    /** The journal line for a failure (fields: reason, position, edge kind, retries, fallback). */
    fun line(reason: Reason, at: P, move: Move, retries: Int, fallback: Boolean) =
        "travel fail  reason=$reason  at=${at.x},${at.y},${at.z}  edge=$move  retries=$retries  fallback=${if (fallback) "walk" else "no"}"
}
