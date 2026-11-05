package com.github.noamm9.features.impl.fishing

/**
 * Auto Fishing's states, in their old order. Plain data (no Minecraft types) so the transition table can be tested.
 * [toString] is the state's name, so fishing.log reads exactly as before ("state CAST -> WAIT_BITE").
 */
sealed class FishingState(val name: String, val ordinal: Int) {
    object FIND : FishingState("FIND", 0)
    object TRAVEL : FishingState("TRAVEL", 1)
    object CAST : FishingState("CAST", 2)
    object WAIT_BITE : FishingState("WAIT_BITE", 3)
    object REEL : FishingState("REEL", 4)
    object FIGHT : FishingState("FIGHT", 5)
    object SELL : FishingState("SELL", 6)
    object BREAK : FishingState("BREAK", 7)
    object RESUME : FishingState("RESUME", 8)

    override fun toString() = name

    companion object {
        val entries: List<FishingState> get() = listOf(FIND, TRAVEL, CAST, WAIT_BITE, REEL, FIGHT, SELL, BREAK, RESUME)
    }
}
