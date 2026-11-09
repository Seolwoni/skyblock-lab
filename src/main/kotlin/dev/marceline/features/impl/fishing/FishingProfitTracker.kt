package dev.marceline.features.impl.fishing

import dev.marceline.Marceline.mc
import dev.marceline.config.types.ButtonSetting
import dev.marceline.config.types.DropdownSetting
import dev.marceline.config.types.SliderSetting
import dev.marceline.config.types.ToggleSetting
import dev.marceline.event.impl.ChatMessageEvent
import dev.marceline.event.impl.TickEvent
import dev.marceline.features.Feature
import dev.marceline.utils.items.ItemUtils.skyblockId
import dev.marceline.utils.tracker.ItemGains
import dev.marceline.utils.tracker.ItemTracker
import dev.marceline.utils.tracker.PriceSource
import dev.marceline.utils.tracker.TrackerHud
import dev.marceline.utils.tracker.TrackerSpan
import dev.marceline.utils.tracker.Trackers
import net.minecraft.world.item.Items

/**
 * Fishing Profit Tracker, after SkyHanni's (FishingProfitTracker, LGPL-2.1, see LICENSE-SKYHANNI.txt): what your
 * catches are worth, per item and per hour. Only fishing loot counts (the list from the SkyHanni repo, MIT), only
 * while you fish. Catches that went into a sack are read from the "[Sacks]" message.
 */
object FishingProfitTracker: Feature("Shows what your fishing brings in: every catch by value, the total and per hour.", toggled = true) {
    private val span by DropdownSetting("Show", 0, TrackerSpan.entries.map { it.title }).withDescription("This session, today, or everything so far")
    private val prices by DropdownSetting("Prices", 0, PriceSource.entries.map { it.title })
    private val hideBelow by SliderSetting("Hide Items Under", 0, 0, 100_000, 1_000, " coins").withDescription("Leaves out items worth less than this in total")
    private val maxLines by SliderSetting("Lines", 12, 3, 30, 1)
    private val onlyFishing by ToggleSetting("Only While Fishing", true).withDescription("Shows the panel only while you fish (and a few seconds after)")
    private val resetSession by ButtonSetting("Reset Session") { tracker.resetSession() }
    private val resetAll by ButtonSetting("Reset All") { tracker.resetTotal() }

    val tracker = Trackers.create("fishing_profit", "Fishing Profit")
    private val allowed: Set<String> by lazy { FishingRepo.profitItems.values.flatten().toSet() }

    private var lastFishingAt = 0L
    private var lastCatchAt = 0L
    /** "GOOD CATCH! You caught 36,064 Coins!" (and GREAT / OUTSTANDING) */
    private val coinsCatch = Regex("""CATCH! You caught ([\d,]+) Coins!""")

    val fishing get() = System.currentTimeMillis() - lastFishingAt < 20_000

    /** A catch (a fishing item came in, or a sea creature / coins came up), at most one per half second. */
    fun catchMade() {
        val now = System.currentTimeMillis()
        if (now - lastCatchAt < 500) return
        lastCatchAt = now
        tracker.count("catches")
    }

    override fun init() {
        register<TickEvent.Start> {
            val p = mc.player ?: return@register
            val held = p.mainHandItem
            if (p.fishing != null || held.item == Items.FISHING_ROD || held.skyblockId.contains("ROD")) lastFishingAt = System.currentTimeMillis()
        }
        ItemGains.listen { id, amount, _ ->
            if (! enabled || ! fishing || id !in allowed) return@listen
            tracker.add(id, amount)
            catchMade()
        }
        register<ChatMessageEvent> {
            if (! fishing) return@register
            coinsCatch.find(event.unformattedText)?.let {
                tracker.add(ItemTracker.COINS, it.groupValues[1].replace(",", "").toLongOrNull() ?: return@let)
                catchMade()
            }
        }
        hudElement("Fishing Profit Tracker", shouldDraw = { ! onlyFishing.value || fishing }) { ctx, example ->
            TrackerHud.draw(ctx, tracker, TrackerHud.Options(TrackerSpan.entries[span.value], PriceSource.entries[prices.value],
                maxLines.value, hideBelow.value.toDouble(), rateCounter = "catches"),
                listOf(TrackerHud.Extra("Catches", dev.marceline.utils.NumbersUtils.formatComma(tracker.bucket(TrackerSpan.entries[span.value]).counter("catches")))), example)
        }
    }
}

/** Fishing data from the SkyHanni repo (MIT, see LICENSE-SKYHANNI-REPO.txt), bundled. */
object FishingRepo {
    private fun read(name: String): com.google.gson.JsonObject? = runCatching {
        FishingRepo::class.java.getResourceAsStream("/assets/marceline/skyhanni-repo/$name")!!.reader().use {
            com.google.gson.JsonParser.parseReader(it).asJsonObject
        }
    }.getOrNull()

    /** Category -> SkyBlock ids of the items fishing can bring. */
    val profitItems: Map<String, List<String>> by lazy {
        val cats = read("FishingProfitItems.json")?.getAsJsonObject("categories") ?: return@lazy emptyMap()
        cats.entrySet().associate { (k, v) -> k to v.asJsonArray.map { it.asString } }
    }

    data class SeaCreature(val name: String, val rarity: String, val group: String, val color: String)

    /** The chat line each sea creature comes up with -> that creature. */
    val seaCreatures: Map<String, SeaCreature> by lazy {
        val root = read("SeaCreatures.json") ?: return@lazy emptyMap()
        val out = HashMap<String, SeaCreature>()
        for ((group, g) in root.entrySet()) {
            val o = g.asJsonObject
            val color = o.get("chat_color")?.asString ?: "§f"
            for ((name, c) in o.getAsJsonObject("sea_creatures")?.entrySet() ?: emptySet()) {
                val co = c.asJsonObject
                val msg = co.get("chat_message")?.asString ?: continue
                out[msg] = SeaCreature(name, co.get("rarity")?.asString ?: "COMMON", group, color)
            }
        }
        out
    }
}
