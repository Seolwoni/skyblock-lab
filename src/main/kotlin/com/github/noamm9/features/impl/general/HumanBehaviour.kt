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
    val mouseGrid by ToggleSetting("Mouse Grid", true).withDescription("View changes come in whole mouse counts for your sensitivity (no in-between amounts a mouse can't make)")
    val stepSpread by SliderSetting("Step Spread", 0.40, 0.0, 1.0, 0.05).withDescription("How much each tick's share of a turn varies (log-normal sigma)")
    val pauseTicks by SliderSetting("Pause Ticks", 8, 0, 30, 1, "%").withDescription("Share of turn ticks where the hand doesn't move")
    val overshoot by ToggleSetting("Overshoot", true).withDescription("Big turns (over 20°) go a little past the target, then come back")
    val overshootMin by SliderSetting("Overshoot Min", 0.1, 0.0, 0.5, 0.01, "x").showIf { overshoot.value }.withDescription("Least overshoot, as a share of the turn (the turn counted up to 10°)")
    val overshootMax by SliderSetting("Overshoot Max", 0.3, 0.0, 0.5, 0.01, "x").showIf { overshoot.value }

    val reactMin by SliderSetting("Reaction Min", 3, 0, 20, 1, " ticks").section("Reactions").withDescription("Shortest wait before acting on something that just appeared (a mob, a bite, a button lighting up)")
    val reactMax by SliderSetting("Reaction Max", 9, 0, 30, 1, " ticks")
    val slips by SliderSetting("Slow Reactions", 6, 0, 30, 1, "%").withDescription("How often a reaction is clearly slow")
    val mistakeRate by SliderSetting("Mistake Rate", 2.0, 0.0, 10.0, 0.5, "%").withDescription("How often an action comes out a little wrong: a cast a bit off, a click a beat late")
    val delaySpread by SliderSetting("Delay Spread", 55, 30, 90, 1, "%").withDescription("How spread out reaction times are: higher = more of them clearly slower than usual")
    val clickMiss by SliderSetting("Click Miss", 2.0, 0.0, 10.0, 0.5, "%").section("Clicks").withDescription("Share of attack clicks that don't go through (tried again a moment later)")
    val clickSpread by SliderSetting("Click Spread", 0.45, 0.0, 1.0, 0.05).withDescription("How much the gap between attack clicks varies (log-normal sigma around the feature's delay)")
    val waypointOffset by SliderSetting("Waypoint Offset", 0.15, 0.0, 0.25, 0.01, " blocks").section("Movement").withDescription("Each waypoint's arrival point is moved by a fresh offset up to this (per axis). The arrival circle shrinks by the same amount, so where you count as arrived stays inside the old circle")
    val startWobble by SliderSetting("Start Wobble", 1.5, 0.0, 5.0, 0.1, "°").withDescription("Starting to walk from standing: with the Slow Reactions chance, the first steps head off by up to this and straighten out")
    val jumpDelay by SliderSetting("Jump Delay", 1.8, 0.0, 6.0, 0.1, " ticks").withDescription("Typical wait between needing a jump and jumping (log-normal)")
    val jumpSpread by SliderSetting("Jump Spread", 0.6, 0.0, 1.2, 0.05).withDescription("How much the jump wait varies (log-normal sigma)")
    val jumpMax by SliderSetting("Jump Delay Max", 5, 0, 10, 1, " ticks")
    val repathEvery by SliderSetting("Re-path Every", 10, 2, 40, 1, " ticks").withDescription("How often the route checks whether to re-plan (back on route, Assist Path takeover)")
    val repathSpread by SliderSetting("Re-path Spread", 30, 0, 80, 5, "%").withDescription("Each interval is moved by up to this share")
    val sprintDelayMin by SliderSetting("Sprint Delay Min", 1, 0, 6, 1, " ticks").withDescription("Ticks between deciding to start / stop sprinting and the key changing")
    val sprintDelayMax by SliderSetting("Sprint Delay Max", 3, 0, 10, 1, " ticks")
    val idleDrift by SliderSetting("Idle Drift", 2.0, 0.0, 6.0, 0.1, "°").withDescription("While standing (waiting, cooldowns) the view wanders in a random walk within this; also the walking pitch")
    val idleStep by SliderSetting("Idle Step", 0.3, 0.05, 1.0, 0.05, "°").withDescription("Size of each step of that walk")
    val drift by SliderSetting("Rhythm Drift", 18, 0, 50, 1, "%").withDescription("How far the overall speed wanders over minutes")
    val fatigue by SliderSetting("Fatigue", 6, 0, 40, 1, "%/h").withDescription("How much slower reactions get per hour of running (up to 2 hours)")
    val breaks by SliderSetting("Short Breaks", 1.5, 0.0, 10.0, 0.5, "%").withDescription("Chance per action of a 1.5-5 s pause")

    override fun init() {
        register<TickEvent.Start> { Humanize.tick() }
        register<com.github.noamm9.event.impl.RenderWorldEvent> { Humanize.frame() }
    }
}
