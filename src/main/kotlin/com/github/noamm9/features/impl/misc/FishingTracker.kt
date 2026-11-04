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
    private val showTrophy by ToggleSetting("Trophy Fish", true).withDescription("Counts trophy fish by tier (Bronze / Silver / Gold / Diamond) from the catch message")
    private val bobberTimer by ToggleSetting("Bobber Timer", true).section("Bobber").withDescription("Shows how long the hook has been out, above the hook")
    private val timerWarn by SliderSetting("Timer Warn After", 20, 5, 60, 1, " s").withDescription("The timer turns red after this long without a bite")
    private val baitAlert by ToggleSetting("Bait Alerts", true).section("Bait").withDescription("Tells you when the bait in your last hotbar slot (Fishing Bag) changes or runs low")
    private val lowBait by SliderSetting("Low Bait At", 20, 1, 200, 1).withDescription("Warns once the bait count drops to this")
    val resetButton by ButtonSetting("Reset Tracker") { reset() }

    private val trophyMsg = Regex("TROPHY FISH! You caught an? (.+?) (BRONZE|SILVER|GOLD|DIAMOND)\\.?$")
    private val tierColor = mapOf("BRONZE" to "§6", "SILVER" to "§7", "GOLD" to "§e", "DIAMOND" to "§b")
    private val trophies = LinkedHashMap<String, IntArray>() // fish -> bronze, silver, gold, diamond
    private var castAt = 0L
    private var baitName: String? = null
    private var baitWarned = false
    private var baitTick = 0
    private var biteAt = 0L

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
            val t = event.unformattedText.removeFormatting()
            if (t.contains("Double Hook!", ignoreCase = true)) doubleHooks++
            trophyMsg.find(t.trim())?.let { m ->
                val tier = listOf("BRONZE", "SILVER", "GOLD", "DIAMOND").indexOf(m.groupValues[2])
                if (tier >= 0) trophies.getOrPut(m.groupValues[1]) { IntArray(4) }[tier]++
            }
        }
        register<com.github.noamm9.event.impl.RenderWorldEvent> {
            if (! bobberTimer.value) return@register
            val hook = mc.player?.fishing ?: return@register
            if (castAt == 0L) return@register
            val end = if (biteAt != 0L) biteAt else System.currentTimeMillis()
            val secs = (end - castAt) / 1000.0
            val col = when {
                biteAt != 0L -> java.awt.Color(80, 255, 140)
                secs >= timerWarn.value -> java.awt.Color(255, 85, 85)
                else -> java.awt.Color.WHITE
            }
            val pos = hook.getPosition(mc.deltaTracker.getGameTimeDeltaPartialTick(true)).add(0.0, 0.9, 0.0)
            with(com.github.noamm9.utils.render.world.Render3D) { event.ctx.renderString(String.format("%.1fs", secs), pos, col, 1.2f, phase = true) }
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
        creatures.clear(); items.clear(); trophies.clear()
        activeMs = 0; lastReel = 0
    }

    /** The bait in the last hotbar slot: its name and the "Bait Remaining: N" count from the lore. */
    private fun watchBait(p: net.minecraft.client.player.LocalPlayer) {
        if (! baitAlert.value || ++baitTick % 40 != 0 || p.fishing == null) return
        val stack = p.inventory.getItem(8)
        if (stack.isEmpty) return
        val lore = with(com.github.noamm9.utils.items.ItemUtils) { stack.lore }.map { it.removeFormatting() }
        val count = lore.firstNotNullOfOrNull { Regex("Bait Remaining: ([\\d,]+)").find(it)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() }
        val name = lore.firstNotNullOfOrNull { Regex("^(?:Selected|Current|Using)(?: Bait)?: (.+)$").find(it)?.groupValues?.get(1) } ?: stack.hoverName.string.removeFormatting()
        if (baitName != null && baitName != name) com.github.noamm9.ui.notification.NotificationManager.push("Bait changed", "&f$baitName &7-> &f$name")
        baitName = name
        if (count != null) {
            if (count <= lowBait.value && ! baitWarned) {
                baitWarned = true
                com.github.noamm9.ui.notification.NotificationManager.push("Low bait", "&f$count &7left")
                com.github.noamm9.utils.SoundUtils.playSound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BELL.value(), 1f, 1f)
            }
            if (count > lowBait.value) baitWarned = false
        }
    }

    private fun tick() {
        val p = mc.player ?: return
        watchBait(p)
        val now = System.currentTimeMillis()
        if (lastTick != 0L && lastReel != 0L && now - lastReel < pauseAfter.value * 1000L) activeMs += now - lastTick
        lastTick = now

        val hook = p.fishing
        if (hook != null) {
            if (! hadHook) {
                casts++
                castAt = now
                biteAt = 0L
                if (hook.isInLava || p.level().getFluidState(hook.blockPosition()).`is`(net.minecraft.tags.FluidTags.LAVA)) lavaCasts++
            }
            hookPos = hook.position()
            if (biteAt == 0L && biteSign(hook)) biteAt = now
        }
        else if (hadHook) {
            // reeled in: watch the inventory and the water around the hook for a moment
            reels++
            lastReel = now
            watchTicks = 30
            before = snapshot()
        }
        hadHook = hook != null
        if (hook == null) castAt = 0L

        if (watchTicks > 0) {
            watchTicks--
            gainedItems()
            hookPos?.let { newCreatures(it) }
        }
    }

    /** SkyBlock puts a "!!!" name tag right over the hook on a bite. */
    private fun biteSign(hook: net.minecraft.world.entity.projectile.FishingHook): Boolean {
        val level = mc.level ?: return false
        val box = hook.boundingBox.inflate(0.6, 2.0, 0.6)
        return com.github.noamm9.utils.FloatingText.any(level, box, "!!!")
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
        for ((stand, text) in com.github.noamm9.utils.FloatingText.near(level, box)) {
            if (! seenStands.add(stand.id)) continue
            val name = text.removeFormatting()
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
        if (showTrophy.value && trophies.isNotEmpty()) {
            out += "§6Trophy Fish §f${trophies.values.sumOf { it.sum() }}"
            trophies.entries.sortedByDescending { it.value.sum() }.take(maxLines.value).forEach { (n, t) ->
                out += " §7$n " + listOf("BRONZE", "SILVER", "GOLD", "DIAMOND").mapIndexedNotNull { i, k -> if (t[i] > 0) "${tierColor[k]}${t[i]}" else null }.joinToString("§8/")
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
        "§6Trophy Fish §f9",
        " §7Sulphur Skitter §66§8/§72§8/§e1",
        "§6Total §f1.2M §8(1M/h)",
    )
}
