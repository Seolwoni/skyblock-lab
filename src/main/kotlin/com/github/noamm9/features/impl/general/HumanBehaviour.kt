package com.github.noamm9.features.impl.general

import com.github.noamm9.config.types.DropdownSetting
import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.utils.Humanize

/**
 * Shared "act like a person" settings for every automatic feature (Auto Routes, Auto Dungeon, Auto Boss,
 * Auto Puzzles, Auto Fishing, Auto Chests, Simon Says / Arrow Align auto). Same ideas as the Assist mods.
 */
object HumanBehaviour: Feature(
    "Makes every automatic feature move and react like a person (Assist mods style): eased curved turns, human reaction times with a drifting rhythm, aim points off-centre, a short reaction before acting on new things. Off = instant and exact.",
    toggled = true
) {
    val pace by DropdownSetting("Pace", 0, listOf("Relaxed", "Normal", "Fast")).withDescription("Relaxed is the most human; Fast shortens waits and turns quicker but keeps the same shape")
    val useAssistCore by ToggleSetting("Use Assist Core", true).withDescription("When Assist Core is installed, turning is done by Assist Core itself")

    val turnSpeed by SliderSetting("Turn Speed", 1.0, 0.5, 2.0, 0.05, "x").section("Turning").withDescription("Top speed of head turns (each turn picks its own speed around this)")
    val curvedTurns by ToggleSetting("Curved Turns", true).withDescription("Turns follow a slight arc instead of a ruler-straight line")
    val aimSpread by SliderSetting("Aim Spread", 0.12, 0.0, 0.45, 0.01, " blocks").withDescription("Aims at a point near the middle of a block / mob, not the exact centre (same spot each time for the same target)")
    val overshoot by ToggleSetting("Overshoot", true).withDescription("Big turns (over 20°) go a little past the target, then come back")
    val overshootMin by SliderSetting("Overshoot Min", 0.1, 0.0, 0.5, 0.01, "x").showIf { overshoot.value }.withDescription("Least overshoot, as a share of the turn (the turn counted up to 10°)")
    val overshootMax by SliderSetting("Overshoot Max", 0.3, 0.0, 0.5, 0.01, "x").showIf { overshoot.value }

    val reactMin by SliderSetting("Reaction Min", 3, 0, 20, 1, " ticks").section("Reactions").withDescription("Shortest wait before acting on something that just appeared (a mob, a bite, a button lighting up)")
    val reactMax by SliderSetting("Reaction Max", 9, 0, 30, 1, " ticks")
    val slips by SliderSetting("Slow Reactions", 6, 0, 30, 1, "%").withDescription("How often a reaction is clearly slow")
    val mistakeRate by SliderSetting("Mistake Rate", 2.0, 0.0, 10.0, 0.5, "%").withDescription("How often an action comes out a little wrong: a cast a bit off, a click a beat late")
    val delaySpread by SliderSetting("Delay Spread", 55, 30, 90, 1, "%").withDescription("How spread out reaction times are: higher = more of them clearly slower than usual")
    val drift by SliderSetting("Rhythm Drift", 18, 0, 50, 1, "%").withDescription("How far the overall speed wanders over minutes")
    val fatigue by SliderSetting("Fatigue", 6, 0, 40, 1, "%/h").withDescription("How much slower reactions get per hour of running (up to 2 hours)")
    val breaks by SliderSetting("Short Breaks", 1.5, 0.0, 10.0, 0.5, "%").withDescription("Chance per action of a 1.5-5 s pause")

    override fun init() {
        register<TickEvent.Start> { Humanize.tick() }
        register<com.github.noamm9.event.impl.RenderWorldEvent> { Humanize.frame() }
    }
}
