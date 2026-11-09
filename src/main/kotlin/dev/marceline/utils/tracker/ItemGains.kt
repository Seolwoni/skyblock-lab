package dev.marceline.utils.tracker

import dev.marceline.Marceline.mc
import dev.marceline.event.EventBus
import dev.marceline.event.impl.ChatMessageEvent
import dev.marceline.event.impl.TickEvent
import dev.marceline.event.impl.WorldChangeEvent
import dev.marceline.init.types.ISelfInit
import dev.marceline.utils.items.ItemUtils.skyblockId
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent

/**
 * Items coming in, for the trackers (after SkyHanni's ItemAddManager): what appears in the inventory while no
 * container is open, and what the "[Sacks] +N items" message lists in its hover (items that went straight into a
 * sack). Listeners get the SkyBlock id and the amount.
 */
object ItemGains: ISelfInit {
    enum class Source { INVENTORY, SACKS }

    fun interface Listener { fun gained(itemId: String, amount: Long, source: Source) }

    private val listeners = ArrayList<Listener>()
    private var last: Map<String, Long>? = null
    private var settle = 0

    fun listen(l: Listener) { listeners += l }

    private fun emit(id: String, amount: Long, source: Source) {
        if (amount <= 0 || id.isBlank()) return
        for (l in listeners) runCatching { l.gained(id, amount, source) }
    }

    /** id -> count over the main inventory and hotbar. */
    private fun snapshot(): Map<String, Long>? {
        val inv = mc.player?.inventory ?: return null
        val out = HashMap<String, Long>()
        for (i in 0 until 36) {
            val s = inv.getItem(i)
            if (s.isEmpty) continue
            val id = s.skyblockId
            if (id.isBlank()) continue
            out[id] = (out[id] ?: 0L) + s.count
        }
        return out
    }

    private fun tick() {
        val screen = mc.gui.screen()
        // items moved out of a chest or a menu aren't gains: watch only with no screen or the own inventory open,
        // and take a fresh baseline a moment after one closes
        if (screen != null && screen !is InventoryScreen) { last = null; settle = 6; return }
        if (settle > 0) { settle--; last = snapshot(); return }
        val now = snapshot() ?: run { last = null; return }
        val before = last
        last = now
        if (before == null) return
        for ((id, n) in now) {
            val gained = n - (before[id] ?: 0L)
            if (gained > 0) emit(id, gained, Source.INVENTORY)
        }
    }

    /** "+64 Magmafish (Fishing Sack)" */
    private val sackLine = Regex("""^\s*\+([\d,]+) (.+?) \((.+?)\)\s*$""")

    private fun sacks(c: Component) {
        if (! c.string.trimStart().startsWith("[Sacks] +")) return
        val hovers = ArrayList<Component>()
        fun walk(x: Component) {
            (x.style.hoverEvent as? HoverEvent.ShowText)?.value?.let { hovers += it }
            x.siblings.forEach(::walk)
        }
        walk(c)
        val text = hovers.firstOrNull { it.string.contains('+') } ?: return
        for (line in text.string.lines()) {
            val m = sackLine.find(line) ?: continue
            val amount = m.groupValues[1].replace(",", "").toLongOrNull() ?: continue
            val id = TrackerPrices.idOf(m.groupValues[2].trim()) ?: continue
            emit(id, amount, Source.SACKS)
        }
    }

    override fun init() {
        EventBus.register<TickEvent.End> { runCatching { tick() } }
        EventBus.register<ChatMessageEvent> { runCatching { sacks(event.component) } }
        EventBus.register<WorldChangeEvent> { last = null; settle = 40 }
        // the trackers' clocks and the occasional save
        var ticks = 0
        EventBus.register<TickEvent.End> {
            Trackers.all.forEach { it.tick() }
            if (++ ticks % 1200 == 0) TrackerStore.flush()
        }
    }
}

/** All trackers, so their clocks tick and they save together. */
object Trackers {
    val all = ArrayList<ItemTracker>()

    fun create(id: String, title: String) = ItemTracker(id, title).also { all += it }
}
