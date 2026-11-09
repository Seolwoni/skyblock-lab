package dev.marceline.utils.tracker

import dev.marceline.config.PogObject
import dev.marceline.init.NetworkLoop
import java.time.LocalDate

/**
 * One tracker (fishing profit, sea creatures, powder...): items and counters per session / day / all time, the time
 * spent at it, and the lines a HUD shows. Feeding it ([add], [count]) also keeps its clock running ([Uptime]).
 */
class ItemTracker(val id: String, val title: String) {
    /** Display names for keys that aren't SkyBlock items (sea creature names...); null: item names. */
    var namer: ((String) -> String)? = null
    private val session = TrackerBucket()
    private var lastChange = 0L
    private var lastTick = 0L

    fun bucket(span: TrackerSpan): TrackerBucket = when (span) {
        TrackerSpan.SESSION -> session
        TrackerSpan.TODAY -> TrackerStore.today(id)
        TrackerSpan.TOTAL -> TrackerStore.total(id)
    }

    private fun all() = listOf(session, TrackerStore.today(id), TrackerStore.total(id))

    /** [amount] of item [itemId] came in (coins: [COINS]). */
    fun add(itemId: String, amount: Long) {
        if (amount <= 0) return
        all().forEach { it.add(itemId, amount) }
        touch()
    }

    /** A named count went up: catches, kills, chests... */
    fun count(name: String, by: Long = 1) {
        all().forEach { it.count(name, by) }
        touch()
    }

    /** Marks the tracker as in use right now (starts / keeps its clock running) without adding anything. */
    fun touch() {
        lastChange = System.currentTimeMillis()
        TrackerStore.dirty()
    }

    /** Once a tick: the active-time clock. */
    fun tick() {
        val now = System.currentTimeMillis()
        val d = Uptime.step(lastTick, now, lastChange)
        lastTick = now
        if (d > 0) all().forEach { it.uptimeMs += d }
    }

    fun resetSession() {
        session.items.clear(); session.counters.clear(); session.uptimeMs = 0
    }

    fun resetTotal() {
        TrackerStore.total(id).let { it.items.clear(); it.counters.clear(); it.uptimeMs = 0 }
        TrackerStore.today(id).let { it.items.clear(); it.counters.clear(); it.uptimeMs = 0 }
        resetSession()
        TrackerStore.dirty()
    }

    val active get() = System.currentTimeMillis() - lastChange < Uptime.DEFAULT_PAUSE_MS

    companion object {
        const val COINS = "SKYBLOCK_COIN"
    }
}

/** How items are valued. */
enum class PriceSource(val title: String) { MARKET("Bazaar / Lowest BIN"), NPC("NPC sell") }

/** Prices and names for tracked items (prices come from [NetworkLoop]). */
object TrackerPrices {
    fun each(itemId: String, source: PriceSource): Double = when {
        itemId == ItemTracker.COINS -> 1.0
        source == PriceSource.NPC -> (NetworkLoop.getNpcSellPrice(itemId) ?: NetworkLoop.getPrice(itemId) ?: 0L).toDouble()
        else -> (NetworkLoop.getPrice(itemId) ?: NetworkLoop.getNpcSellPrice(itemId) ?: 0L).toDouble()
    }

    private val idToName = HashMap<String, String>()

    /** A readable name for a SkyBlock id ("MAGMA_FISH" -> "Magmafish" when the item list knows it). */
    fun name(itemId: String): String {
        if (itemId == ItemTracker.COINS) return "Coins"
        if (idToName.size < NetworkLoop.nameToIdMap.size / 2) {
            NetworkLoop.nameToIdMap.forEach { (name, id) -> idToName.putIfAbsent(id, name) }
        }
        return idToName[itemId] ?: itemId.lowercase().split('_').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }

    /** A SkyBlock id for an item name as chat shows it ("Magmafish" -> "MAGMA_FISH"), or null. */
    fun idOf(name: String): String? = NetworkLoop.nameToIdMap[name] ?: NetworkLoop.nameToIdMap.entries.firstOrNull { it.key.equals(name, true) }?.value
}

/** Every tracker's saved numbers (config/Marceline/trackers.json). */
object TrackerStore {
    private val pog = PogObject("trackers", TrackerSave())
    private const val KEEP_DAYS = 60
    @Volatile private var changed = false

    fun total(id: String): TrackerBucket = pog.get().total.getOrPut(id) { TrackerBucket() }

    fun today(id: String): TrackerBucket {
        val days = pog.get().days
        val key = LocalDate.now().toString()
        if (key !in days && days.size >= KEEP_DAYS) days.keys.sorted().take(days.size - KEEP_DAYS + 1).forEach { days.remove(it) }
        return days.getOrPut(key) { HashMap() }.getOrPut(id) { TrackerBucket() }
    }

    fun dirty() { changed = true }

    /** Writes when something changed (called now and then, and the store saves itself on exit). */
    fun flush() {
        if (! changed) return
        changed = false
        pog.save()
    }
}
