package dev.riftwalk.features.impl.fishing

import dev.riftwalk.config.types.ButtonSetting
import dev.riftwalk.config.types.SliderSetting
import dev.riftwalk.config.types.ToggleSetting
import dev.riftwalk.event.impl.ChatMessageEvent
import dev.riftwalk.event.impl.TickEvent
import dev.riftwalk.features.Feature
import dev.riftwalk.init.NetworkLoop
import dev.riftwalk.utils.ChatUtils.removeFormatting
import dev.riftwalk.utils.items.ItemUtils.skyblockId
import dev.riftwalk.utils.render.Render2D.drawString
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.Vec3

/**
 * Fishing tracker (like SkyHanni's fishing trackers): counts casts, catches, sea creatures and the items you fish up,
 * with their coin value, per hour. Works for manual fishing and Auto Fishing, in water and in lava.
 * Sea creatures are read from the name tags that come up at the hook, items from what lands in the inventory
 * right after reeling in, so it also works on servers with their own creatures and items.
 */
object FishingTracker: Feature("Bobber timer and bait alerts. Also counts catches in the background for the Auto Fishing report (nothing on screen).") {
    private val bobberTimer by ToggleSetting("Bobber Timer", true).section("Bobber").withDescription("Shows how long the hook has been out, above the hook")
    private val timerWarn by SliderSetting("Timer Warn After", 20, 5, 60, 1, " s").withDescription("The timer turns red after this long without a bite")
    private val baitAlert by ToggleSetting("Bait Alerts", true).section("Bait").withDescription("Tells you when the bait in your last hotbar slot (Fishing Bag) changes or runs low")
    private val lowBait by SliderSetting("Low Bait At", 20, 1, 200, 1).withDescription("Warns once the bait count drops to this")
    private val hideOtherHooks by ToggleSetting("Hide Other Hooks", false).section("Bobber").withDescription("Hides other players' bobbers and fishing lines, so only yours shows")
    val resetButton by ButtonSetting("Reset Tracker") { reset() }

    private val trophyMsg = dev.riftwalk.utils.SkyblockText.TROPHY_CATCH
    private val trophies = LinkedHashMap<String, IntArray>() // fish -> bronze, silver, gold, diamond
    private var castAt = 0L
    private var baitName: String? = null
    private var baitWarned = false
    private var baitTick = 0
    private var biteAt = 0L

    private val creatureTag = dev.riftwalk.utils.SkyblockText.MOB_TAG

    private var casts = 0
    private var reels = 0
    private var doubleHooks = 0
    private val creatures = LinkedHashMap<String, Int>()
    private val items = LinkedHashMap<String, Pair<Int, Long>>() // name -> count, coins
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
        register<dev.riftwalk.event.impl.CheckEntityRenderEvent> {
            val e = event.entity
            if (hideOtherHooks.value && e is net.minecraft.world.entity.projectile.FishingHook && e.playerOwner !== mc.player) event.isCanceled = true
        }
        register<TickEvent.Start> { dev.riftwalk.utils.Safely("FishingTracker tick") { tick() } }
        register<ChatMessageEvent> {
            val t = event.unformattedText.removeFormatting()
            if (t.contains(dev.riftwalk.utils.SkyblockText.DOUBLE_HOOK, ignoreCase = true)) doubleHooks++
            trophyMsg.find(t.trim())?.let { m ->
                val tier = listOf("BRONZE", "SILVER", "GOLD", "DIAMOND").indexOf(m.groupValues[2])
                if (tier >= 0) trophies.getOrPut(m.groupValues[1]) { IntArray(4) }[tier]++
            }
        }
        register<dev.riftwalk.event.impl.RenderWorldEvent> {
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
            with(dev.riftwalk.utils.render.world.Render3D) { event.ctx.renderString(String.format("%.1fs", secs), pos, col, 1.2f, phase = true) }
        }
    }

    /** Running totals, for the Auto Fishing session report (it takes the difference from the start). */
    fun numbers(): Map<String, Long> = mapOf(
        "casts" to casts.toLong(), "reels" to reels.toLong(), "doubleHooks" to doubleHooks.toLong(),
        "coins" to items.values.sumOf { it.second }, "trophies" to trophies.values.sumOf { it.sum() }.toLong()
    )

    /** The item that made the most coins so far ("Magma Core x23"). */
    fun topItem(): String? = items.entries.maxByOrNull { it.value.second }?.takeIf { it.value.second > 0 }?.let { "${it.key} x${it.value.first}" }

    fun reset() {
        casts = 0; reels = 0; doubleHooks = 0; lavaCasts = 0
        creatures.clear(); items.clear(); trophies.clear()
        lastReel = 0
    }

    /** The bait in the last hotbar slot: its name and the "Bait Remaining: N" count from the lore. */
    private fun watchBait(p: net.minecraft.client.player.LocalPlayer) {
        if (! baitAlert.value || ++baitTick % 40 != 0 || p.fishing == null) return
        val stack = p.inventory.getItem(8)
        if (stack.isEmpty) return
        val lore = with(dev.riftwalk.utils.items.ItemUtils) { stack.lore }.map { it.removeFormatting() }
        val count = lore.firstNotNullOfOrNull { dev.riftwalk.utils.SkyblockText.BAIT_REMAINING.find(it)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() }
        val name = lore.firstNotNullOfOrNull { Regex("^(?:Selected|Current|Using)(?: Bait)?: (.+)$").find(it)?.groupValues?.get(1) } ?: stack.hoverName.string.removeFormatting()
        if (baitName != null && baitName != name) dev.riftwalk.ui.notification.NotificationManager.push("Bait changed", "&f$baitName &7-> &f$name")
        baitName = name
        if (count != null) {
            if (count <= lowBait.value && ! baitWarned) {
                baitWarned = true
                dev.riftwalk.ui.notification.NotificationManager.push("Low bait", "&f$count &7left")
                dev.riftwalk.utils.SoundUtils.playSound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BELL.value(), 1f, 1f)
            }
            if (count > lowBait.value) baitWarned = false
        }
    }

    private fun tick() {
        val p = mc.player ?: return
        watchBait(p)
        val now = System.currentTimeMillis()
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
        return dev.riftwalk.utils.FloatingText.any(level, box, dev.riftwalk.utils.SkyblockText.BITE)
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
        for ((stand, text) in dev.riftwalk.utils.FloatingText.near(level, box)) {
            if (! seenStands.add(stand.id)) continue
            val name = text.removeFormatting()
            val m = creatureTag.find(name) ?: continue
            val creature = m.groupValues[1].trim()
            if (creature.isBlank()) continue
            creatures[creature] = (creatures[creature] ?: 0) + 1
        }
        if (seenStands.size > 4000) seenStands.clear()
    }
}
