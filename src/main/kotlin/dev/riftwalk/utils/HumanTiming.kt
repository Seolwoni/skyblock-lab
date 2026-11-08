package dev.riftwalk.utils

import java.util.Random
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Reaction-time model for the automatic features, so their rhythm looks like a person's instead of a dice roll:
 *
 * - **Skewed spread**: reaction times are log-normal (most fast, a long tail of slower ones), not flat or symmetric.
 * - **Rhythm drift**: a slow random walk speeds the whole rhythm up and down over minutes.
 * - **Carry-over**: each delay leans a little towards the last one (people stay in a groove for a while).
 * - **Fatigue**: reactions slow down slightly the longer a session runs.
 * - **Slips**: now and then a clearly slow reaction.
 * - **No repeats**: the same tick count never comes twice in a row.
 * - **Breaks**: [maybeBreak] gives the odd longer pause (looking at chat, the inventory...).
 *
 * Every result stays inside the [min, max] the caller asks for.
 */
class HumanTiming(seed: Long = System.nanoTime(), private val clock: () -> Long = System::currentTimeMillis) {
    private val rnd = Random(seed)
    private var tempo = 1.0
    private var last = - 1.0
    private var lastTicks = - 1
    private val started = clock()

    /** Slips: chance of a clearly slow reaction (0..1). */
    var slipChance = 0.06
    /** How strongly the last delay pulls the next one (0..1). */
    var carryOver = 0.35
    /** Extra slowness after an hour of running (0.15 = 15% slower). Grows linearly, capped at 2 hours. */
    var fatiguePerHour = 0.08
    /** How much the rhythm can drift (0.2 = between 80% and 120% speed). */
    var drift = 0.2
    /** Where the typical reaction sits in the range (0.28 = 28% of the way from min to max). */
    var median = 0.28
    /** Delay spread: the log-normal sigma. Higher = a longer tail of slow reactions. */
    var spread = 0.55

    /** Takes the spread and slip rate from a [HumanProfile]. */
    fun apply(profile: HumanProfile): HumanTiming {
        spread = profile.delaySpread
        slipChance = profile.slipChance
        return this
    }

    /** Call once per tick so the rhythm drifts smoothly. */
    fun tick() {
        tempo += rnd.nextGaussian() * 0.0015
        tempo += (1.0 - tempo) * 0.0004 // pulled back to normal slowly
        tempo = tempo.coerceIn(1.0 - drift, 1.0 + drift)
    }

    /** A delay in ticks in [min, max] following the model. */
    fun next(min: Int, max: Int): Int {
        val lo = minOf(min, max)
        val hi = maxOf(min, max)
        if (hi <= lo) return lo
        val span = (hi - lo).toDouble()

        // log-normal: median ~28% into the range, long tail to the right
        var x = exp(ln(median) + rnd.nextGaussian() * spread) // 0..~1+, mostly 0.15-0.5
        if (rnd.nextDouble() < slipChance) x = 0.6 + rnd.nextDouble() * 0.4

        val hours = ((clock() - started) / 3_600_000.0).coerceAtMost(2.0)
        x *= tempo * (1.0 + fatiguePerHour * hours)
        if (last >= 0) x = x * (1 - carryOver) + last * carryOver
        x = x.coerceIn(0.0, 1.0)
        last = x

        var t = (lo + x * span).roundToInt().coerceIn(lo, hi)
        if (t == lastTicks) t = if (t < hi && (t == lo || rnd.nextBoolean())) t + 1 else t - 1
        lastTicks = t
        return t
    }

    /** With [chancePercent] % odds, a break of [minTicks]..[maxTicks] ticks; otherwise 0. */
    fun maybeBreak(chancePercent: Double, minTicks: Int, maxTicks: Int): Int {
        if (chancePercent <= 0 || rnd.nextDouble() * 100 >= chancePercent) return 0
        return minTicks + rnd.nextInt((maxTicks - minTicks).coerceAtLeast(0) + 1)
    }

    /** True with the profile's failure rate: the odd fumble (an aim a bit off, a click a beat late). */
    fun fails(failRate: Double) = failRate > 0 && rnd.nextDouble() < failRate

    /** Plain uniform pick, for things that shouldn't follow the rhythm. */
    fun uniform(min: Int, max: Int) = if (max <= min) min else min + rnd.nextInt(max - min + 1)
}
