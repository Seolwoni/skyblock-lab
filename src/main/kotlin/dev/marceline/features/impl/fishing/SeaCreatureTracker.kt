package dev.marceline.features.impl.fishing

import dev.marceline.config.types.ButtonSetting
import dev.marceline.config.types.DropdownSetting
import dev.marceline.config.types.SliderSetting
import dev.marceline.config.types.ToggleSetting
import dev.marceline.event.impl.ChatMessageEvent
import dev.marceline.features.Feature
import dev.marceline.utils.NumbersUtils
import dev.marceline.utils.tracker.PriceSource
import dev.marceline.utils.tracker.TrackerHud
import dev.marceline.utils.tracker.TrackerSpan
import dev.marceline.utils.tracker.Trackers

/**
 * Sea Creature Tracker, after SkyHanni's (SeaCreatureTracker, LGPL-2.1, see LICENSE-SKYHANNI.txt): every sea creature
 * you fish up, by name in its rarity colour, read from the line each one surfaces with (SkyHanni repo, MIT).
 * Double Hooks count two. Also says how many catches since the last rare one.
 */
object SeaCreatureTracker: Feature("Counts the sea creatures you fish up, by kind, with the time since the last rare one.", toggled = true) {
    private val span by DropdownSetting("Show", 0, TrackerSpan.entries.map { it.title })
    private val maxLines by SliderSetting("Lines", 12, 3, 40, 1)
    private val onlyFishing by ToggleSetting("Only While Fishing", true)
    private val resetSession by ButtonSetting("Reset Session") { tracker.resetSession() }
    private val resetAll by ButtonSetting("Reset All") { tracker.resetTotal() }

    val tracker = Trackers.create("sea_creatures", "Sea Creatures").apply {
        namer = { key -> FishingRepo.seaCreatures.values.firstOrNull { it.name == key }?.let { rarityColor(it.rarity) + it.name } ?: key }
    }

    private var doubleHookAt = 0L
    private val rare = setOf("LEGENDARY", "MYTHIC")

    private fun rarityColor(r: String) = when (r) {
        "UNCOMMON" -> "§a"; "RARE" -> "§9"; "EPIC" -> "§5"; "LEGENDARY" -> "§6"; "MYTHIC" -> "§d"; else -> "§f"
    }

    override fun init() {
        register<ChatMessageEvent> {
            val line = event.unformattedText.trim()
            if (line.contains("Double Hook!")) { doubleHookAt = System.currentTimeMillis(); return@register }
            val sc = FishingRepo.seaCreatures[line] ?: return@register
            val n = if (System.currentTimeMillis() - doubleHookAt < 2_000) 2L else 1L
            doubleHookAt = 0L
            tracker.add(sc.name, n)
            tracker.count("creatures", n)
            if (sc.rarity in rare) tracker.bucket(TrackerSpan.TOTAL).counters["since_rare"] = 0L
            else listOf(TrackerSpan.SESSION, TrackerSpan.TODAY, TrackerSpan.TOTAL).forEach { tracker.bucket(it).count("since_rare", n) }
            FishingProfitTracker.catchMade()
        }
        hudElement("Sea Creature Tracker", shouldDraw = { ! onlyFishing.value || FishingProfitTracker.fishing }) { ctx, example ->
            val b = tracker.bucket(TrackerSpan.entries[span.value])
            TrackerHud.draw(ctx, tracker, TrackerHud.Options(TrackerSpan.entries[span.value], PriceSource.MARKET, maxLines.value, countsOnly = true, rateCounter = "creatures"),
                listOf(
                    TrackerHud.Extra("Creatures", NumbersUtils.formatComma(b.counter("creatures"))),
                    TrackerHud.Extra("Since a rare one", NumbersUtils.formatComma(tracker.bucket(TrackerSpan.TOTAL).counter("since_rare"))),
                ), example)
        }
    }
}
