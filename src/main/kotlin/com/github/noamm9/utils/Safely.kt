package com.github.noamm9.utils

import com.github.noamm9.NoammAddons

/**
 * Runs [block] and, if it throws, keeps the game going but says so: the first failure of each [tag] goes to the
 * log with its stack trace, later ones are only counted (every 100th is logged again), so a broken feature is
 * visible in latest.log instead of silently doing nothing.
 */
object Safely {
    private val counts = HashMap<String, Int>()

    inline operator fun invoke(tag: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { report(tag, t) }
    }

    fun report(tag: String, t: Throwable) {
        val n = (counts[tag] ?: 0) + 1
        counts[tag] = n
        if (n == 1) NoammAddons.logger.error("[$tag] failed", t)
        else if (n % 100 == 0) NoammAddons.logger.warn("[$tag] failed $n times, last: ${t.javaClass.simpleName}: ${t.message}")
    }
}
