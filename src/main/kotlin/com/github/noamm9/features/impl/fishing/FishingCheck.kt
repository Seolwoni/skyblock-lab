package com.github.noamm9.features.impl.fishing

//#if CHEAT
import com.github.noamm9.NoammAddons.mc
import com.github.noamm9.utils.FloatingText
import com.github.noamm9.utils.ManaGuard
import com.github.noamm9.utils.SkyblockText
import com.github.noamm9.utils.items.ItemUtils.lore
import com.github.noamm9.utils.items.ItemUtils.skyblockId

/**
 * One button that shows what the mod can actually read on this server, so the guesses in [SkyblockText]
 * can be checked in a minute: hotbar IDs, every floating text around, the hook's state, tab stats, mana, bait.
 * Everything also goes to the run log.
 */
internal fun AutoFishing.fishingCheck() {
    val p = mc.player ?: return
    fun out(line: String) { info(line); journal.log("check  $line") }
    out("&d&l--- Fishing Check ---")

    // hotbar
    for (i in 0 until 9) {
        val st = p.inventory.getItem(i)
        if (st.isEmpty) continue
        val id = st.skyblockId.ifBlank { "-" }
        val tag = when {
            id in SkyblockText.WITHER_BLADES -> " &a(Hyperion)"
            id in SkyblockText.TELEPORT_SWORDS -> " &a(AOTV)"
            id == SkyblockText.SOUL_WHIP || id == SkyblockText.FLAMING_FLAY -> " &a(whip)"
            id.contains(SkyblockText.HOTSPOT_RADAR) || st.hoverName.string.contains(SkyblockText.HOTSPOT_RADAR_NAME, true) -> " &a(radar)"
            st.item is net.minecraft.world.item.FishingRodItem -> " &a(rod)"
            else -> ""
        }
        out("&7slot ${i + 1}: &f${st.hoverName.string} &8[$id]$tag")
    }

    // floating texts nearby, the interesting ones first
    val texts = FloatingText.near(p.level(), p.boundingBox.inflate(30.0, 15.0, 30.0))
        .map { (e, t) -> Triple(e, t.replace(Regex("§."), "").trim(), p.distanceTo(e)) }
        .filter { it.second.isNotEmpty() }
    fun kind(t: String) = when {
        t.contains(SkyblockText.HOTSPOT, true) -> "&dHOTSPOT"
        t.contains(SkyblockText.BITE) -> "&cBITE"
        SkyblockText.WAITING_MARKER.matches(t) -> "&eWAITING"
        SkyblockText.MOB_TAG.containsMatchIn(t) -> "&bMOB"
        else -> "&8other"
    }
    val sorted = texts.sortedWith(compareBy({ if (kind(it.second) == "&8other") 1 else 0 }, { it.third }))
    out("&7floating texts within 30: &f${texts.size} &8(hotspot ${texts.count { kind(it.second) == "&dHOTSPOT" }}, mob tags ${texts.count { kind(it.second) == "&bMOB" }})")
    for ((e, t, d) in sorted.take(12)) out("  ${kind(t)} &f\"${t.take(48)}\" &8${e.javaClass.simpleName} ${d.toInt()}m")

    // the hook
    val hook = p.fishing
    if (hook == null) out("&7hook: &fnone &8(cast to check the bite / waiting markers)")
    else out("&7hook: &fin fluid=${inFluid(hook)} waiting marker=${waitingMarker(hook)} bite=${bitten(hook)} lava=${hook.isInLava} water=${hook.isInWater}")

    // stats, mana, bait, catch lines
    out("&7tab stats: &f${readTabStats().ifBlank { "none found" }}")
    out("&7mana: &f${ManaGuard.mana?.let { "$it (${((ManaGuard.fraction ?: 0f) * 100).toInt()}%)" } ?: "not on the action bar"}")
    val bait = p.inventory.getItem(8)
    if (! bait.isEmpty) {
        val lore = bait.lore.map { it.replace(Regex("§."), "") }
        val count = lore.firstNotNullOfOrNull { SkyblockText.BAIT_REMAINING.find(it)?.groupValues?.get(1) }
        out("&7slot 9: &f${bait.hoverName.string} &8bait count: ${count ?: "no 'Bait Remaining' line"}")
    }
    out("&7catch lines seen: &f${catches.size} &8(messages that named a creature this session show as 'catch line' in the log)")
    out("&8full output saved to the run log")
}
//#endif
