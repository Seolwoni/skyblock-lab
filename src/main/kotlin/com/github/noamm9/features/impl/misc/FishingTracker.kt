package com.github.noamm9.features.impl.misc

import com.github.noamm9.config.types.ButtonSetting
import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.ChatMessageEvent
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.init.NetworkLoop
import com.github.noamm9.utils.ChatUtils.removeFormatting
import com.github.noamm9.utils.NumbersUtils
import com.github.noamm9.utils.items.ItemUtils.skyblockId
import com.github.noamm9.utils.render.Render2D.drawString
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.Vec3

/**
 * Fishing tracker (like SkyHanni's fishing trackers): counts casts, catches, sea creatures and the items you fish up,
 * with their coin value, per hour. Works for manual fishing and Auto Fishing, in water and in lava.
 * Sea creatures are read from the name tags that come up at the hook, items from what lands in the inventory
 * right after reeling in, so it also works on servers with their own creatures and items.
 */
object FishingTracker: Feature("Tracks your fishing: catches, sea creatures and fished-up items with their coin value, per hour (water and lava).") {
    private val showCreatures by ToggleSetting("Sea Creatures", true).section("Display")
    private val showItems by ToggleSetting("Items", true)
    private val showProfit by ToggleSetting("Coin Value", true).withDescription("Adds what the items are worth (bazaar sell / lowest BIN)")
    private val maxLines by SliderSetting("Lines Per List", 6, 2, 20, 1)
    private val pauseAfter by SliderSetting("Pause After", 60, 10, 600, 10, " s").withDescription("The per-hour numbers stop the clock when you haven't reeled in for this long")
    val resetButton by ButtonSetting("Reset Tracker") { reset() }

    private val creatureTag = Regex("^(?:\\[Lv\\d+] )?(.+?) [\\d,.]+[kKmMbB]?(?:/[\\d,.]+[kKmMbB]?)?❤")

    private var casts = 0
    private var reels = 0
    private var doubleHooks = 0
    private val creatures = LinkedHashMap<String, Int>()
    private val items = LinkedHashMap<String, Pair<Int, Long>>() // name -> count, coins
    private var activeMs = 0L
    private var lastReel = 0L
    private var lastTick = 0L
    private var lavaCasts = 0

    // per-reel tracking
    private var hadHook = false
    private var hookPos: Vec3? = null
    private var watchTicks = 0
    private var before: Map<String, Pair<Int, String>> = emptyMap()
    private val seenStands = HashSet<Int>()

    override fun init() {
        register<TickEvent.Start> { runCatching { tick() } }
        register<ChatMessageEvent> {
            if (event.unformattedText.contains("Double Hook!", ignoreCase = true)) doubleHooks++
        }

        hudElement("Fishing Tracker") { ctx, example ->
            val lines = if (example) exampleLines() else lines()
            if (lines.isEmpty()) return@hudElement 0f to 0f
            lines.forEachIndexed { i, l -> ctx.drawString(l, 0, i * 10) }
            lines.maxOf { with(com.github.noamm9.utils.render.RenderHelper) { it.width() } } to lines.size * 10
        }
    }

    fun reset() {
        casts = 0; reels = 0; doubleHooks = 0; lavaCasts = 0
        creatures.clear(); items.clear()
        activeMs = 0; lastReel = 0
    }

    private fun tick() {
        val p = mc.player ?: return
        val now = System.currentTimeMillis()
        if (lastTick != 0L && lastReel != 0L && now - lastReel < pauseAfter.value * 1000L) activeMs += now - lastTick
        lastTick = now

        val hook = p.fishing
        if (hook != null) {
            if (! hadHook) {
                casts++
                if (hook.isInLava || p.level().getFluidState(hook.blockPosition()).`is`(net.minecraft.tags.FluidTags.LAVA)) lavaCasts++
            }
            hookPos = hook.position()
        }
        else if (hadHook) {
            // reeled in: watch the inventory and the water around the hook for a moment
            reels++
            lastReel = now
            watchTicks = 30
            before = snapshot()
        }
        hadHook = hook != null

        if (watchTicks > 0) {
            watchTicks--
            gainedItems()
            hookPos?.let { newCreatures(it) }
        }
    }

    private fun snapshot(): Map<String, Pair<Int, String>> {
        val inv = mc.player?.inventory ?: return emptyMap()
        val out = HashMap<String, Pair<Int, String>>()
        for (i in 0 until 36) {
            val s = inv.getItem(i)
            if (s.isEmpty) continue
            val id = s.skyblockId.ifBlank { s.item.toString() }
            val prev = out[id]
            out[id] = ((prev?.first ?: 0) + s.count) to s.hoverName.string.removeFormatting()
        }
        return out
    }

    private fun gainedItems() {
        val now = snapshot()
        for ((id, v) in now) {
            val gained = v.first - (before[id]?.first ?: 0)
            if (gained <= 0) continue
            val price = (NetworkLoop.getPrice(id) as? Number)?.toLong() ?: 0L
            val prev = items[v.second]
            items[v.second] = ((prev?.first ?: 0) + gained) to ((prev?.second ?: 0L) + price * gained)
        }
        before = now
    }

    private fun newCreatures(at: Vec3) {
        val level = mc.level ?: return
        val box = net.minecraft.world.phys.AABB(at, at).inflate(5.0, 4.0, 5.0)
        for (stand in level.getEntitiesOfClass(ArmorStand::class.java, box)) {
            if (! seenStands.add(stand.id)) continue
            val name = stand.customName?.string?.removeFormatting() ?: continue
            val m = creatureTag.find(name) ?: continue
            val creature = m.groupValues[1].trim()
            if (creature.isBlank()) continue
            creatures[creature] = (creatures[creature] ?: 0) + 1
        }
        if (seenStands.size > 4000) seenStands.clear()
    }

    private fun perHour(n: Number): String {
        val h = activeMs / 3_600_000.0
        if (h < 0.01) return "-"
        return NumbersUtils.format((n.toDouble() / h).toLong())
    }

    private fun lines(): List<String> {
        if (casts == 0 && reels == 0) return emptyList()
        val out = ArrayList<String>()
        val mins = activeMs / 60000
        out += "§dFishing Tracker §8(${mins / 60}h ${mins % 60}m)"
        out += "§7Catches §f$reels §8(${perHour(reels)}/h)  §7casts §f$casts" + (if (lavaCasts > 0) " §6($lavaCasts lava)" else "")
        if (doubleHooks > 0) out += "§7Double Hooks §f$doubleHooks"
        val totalCreatures = creatures.values.sum()
        if (showCreatures.value && totalCreatures > 0) {
            out += "§bSea Creatures §f$totalCreatures §8(${perHour(totalCreatures)}/h)"
            creatures.entries.sortedByDescending { it.value }.take(maxLines.value).forEach { (n, c) -> out += " §7$n §f$c" }
        }
        if (showItems.value && items.isNotEmpty()) {
            out += "§aItems"
            items.entries.sortedByDescending { if (showProfit.value) it.value.second else it.value.first.toLong() }.take(maxLines.value).forEach { (n, v) ->
                out += " §7$n §fx${v.first}" + if (showProfit.value && v.second > 0) " §6${NumbersUtils.format(v.second)}" else ""
            }
        }
        if (showProfit.value) {
            val total = items.values.sumOf { it.second }
            out += "§6Total §f${NumbersUtils.format(total)} §8(${perHour(total)}/h)"
        }
        return out
    }

    private fun exampleLines() = listOf(
        "§dFishing Tracker §8(1h 12m)",
        "§7Catches §f512 §8(426/h)  §7casts §f530",
        "§bSea Creatures §f61 §8(50/h)",
        " §7Sea Walker §f22",
        " §7Night Squid §f14",
        "§aItems",
        " §7Raw Fish §fx210 §6420",
        "§6Total §f1.2M §8(1M/h)",
    )
}
