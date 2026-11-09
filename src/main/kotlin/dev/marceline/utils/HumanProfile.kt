package dev.marceline.utils

/**
 * How human an automatic feature behaves, passed in rather than hard-coded:
 * [failRate] - share of actions that come out a little wrong (0..1),
 * [delaySpread] - log-normal sigma of reaction delays (bigger = longer tail of slow reactions),
 * [slipChance] - share of clearly slow reactions (0..1),
 * [pitchShare] - the pitch axis' top turning speed as a share of yaw's (0.5..1): up / down trails left / right,
 * [pitchLag] - ticks the pitch axis starts after yaw on a big new turn (0..5).
 * Features get theirs from [Humanize.profile] (Human Behaviour settings) unless they have their own.
 */
data class HumanProfile(
    val failRate: Double = 0.02,
    val delaySpread: Double = 0.55,
    val slipChance: Double = 0.06,
    val pitchShare: Double = 0.75,
    val pitchLag: Int = 2,
) {
    init {
        require(failRate in 0.0 .. 1.0) { "failRate $failRate" }
        require(delaySpread in 0.05 .. 2.0) { "delaySpread $delaySpread" }
        require(slipChance in 0.0 .. 1.0) { "slipChance $slipChance" }
        require(pitchShare in 0.5 .. 1.0) { "pitchShare $pitchShare" }
        require(pitchLag in 0 .. 5) { "pitchLag $pitchLag" }
    }
}
