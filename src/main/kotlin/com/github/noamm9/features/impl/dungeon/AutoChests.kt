package com.github.noamm9.features.impl.dungeon

//#if CHEAT
import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.utils.ChatUtils.modMessage
import com.github.noamm9.utils.ChatUtils.removeFormatting
import com.github.noamm9.utils.ChatUtils.unformattedText
import com.github.noamm9.utils.GuiUtils
import com.github.noamm9.utils.NumbersUtils
import com.github.noamm9.utils.items.ItemUtils.lore
import com.github.noamm9.utils.location.LocationUtils
import com.github.noamm9.utils.location.WorldType
import net.minecraft.client.gui.screens.inventory.ContainerScreen

/**
 * Buys the most profitable dungeon reward chest using Chest Profit's numbers:
 * in a Croesus run it opens the best chest and buys it; at the end of a run it buys a chest you open
 * if it clears the minimum profit. Never pays more than Max Cost.
 */
object AutoChests: Feature("Buys the most profitable reward chest for you (Croesus picks the best of the run; at the end of a run it buys a chest you open if it's worth it). Needs Chest Profit on.") {
    private val croesus by ToggleSetting("Croesus", true).section("Where").withDescription("In a run's Croesus page: opens the chest with the best profit and buys it")
    private val endOfRun by ToggleSetting("End Of Run Chests", false).withDescription(
        "When you open a reward chest in the boss room and it clears Min Profit, buys it. Only one chest per run can be bought with coins, so Croesus picks better"
    )
    private val minProfit by SliderSetting("Min Profit", 0.0, - 2.0, 20.0, 0.1, "M").section("Money").withDescription("Only buys a chest that makes at least this much (below 0 also buys small losses, e.g. for essence)")
    private val maxCost by SliderSetting("Max Cost", 10.0, 0.0, 100.0, 0.5, "M").withDescription("Never pays more than this for one chest")
    private val clickDelay by SliderSetting("Click Delay", 6, 2, 20, 1, " ticks").section("Timing").withDescription("Ticks to wait after a menu opens before clicking (lets the items load)")
    private val chatInfo by ToggleSetting("Chat Messages", true)

    private val croesusRunRegex = Regex("^(Master )?Catacombs - Flo(or (IV|V?I{0,3}))?$")
    private const val BUY_SLOT = 31

    private var lastTitle: String? = null
    private var openTicks = 0
    private var openWait = 6
    private var acted = false
    /** The chest we opened from Croesus, so its page buys right away. */
    private var picked: ChestProfit.DungeonChest? = null
    private var boughtThisRun = false

    override fun init() {
        register<WorldChangeEvent> {
            picked = null
            boughtThisRun = false
        }

        register<TickEvent.Start> {
            val screen = mc.gui.screen() as? ContainerScreen
            val title = screen?.title?.unformattedText
            if (title != lastTitle) {
                lastTitle = title
                openTicks = 0
                openWait = com.github.noamm9.utils.Humanize.around(clickDelay.value)
                acted = false
            }
            if (screen == null || title == null || acted) return@register
            if (! LocationUtils.world.let { it == WorldType.DungeonHub || it == WorldType.Catacombs }) return@register
            if (! ChestProfit.enabled) return@register
            if (++openTicks < openWait) return@register

            when {
                croesus.value && title.matches(croesusRunRegex) -> pickInCroesus()
                ChestProfit.DungeonChest.getFromName(title) != null -> buyHere(ChestProfit.DungeonChest.getFromName(title)!!)
            }
        }
    }

    private fun info(msg: String) {
        if (chatInfo.value) modMessage(msg)
    }

    private fun cost(lore: List<String>): Long = lore.map { it.removeFormatting() }.firstNotNullOfOrNull { line ->
        if (line.contains("FREE")) 0L
        else if (line.contains(" Coins")) line.substringBefore(" ").replace(",", "").toLongOrNull()
        else null
    } ?: 0L

    private fun pickInCroesus() {
        acted = true
        val menu = mc.player?.containerMenu ?: return
        val options = ChestProfit.DungeonChest.entries.filter { it.openedInSequence && it.slot in 10 .. 16 }.filter { chest ->
            val stack = menu.getSlot(chest.slot).item
            val lore = stack.lore
            lore.lastOrNull() != "§aAlready opened!" && cost(lore) <= maxCost.value * 1_000_000 && chest.profit >= minProfit.value * 1_000_000
        }
        val best = options.maxByOrNull { it.profit } ?: return info("&7No chest in this run is worth buying (min ${minProfit.value}M).")
        picked = best
        info("&7Best chest: &d${best.displayText} &7(${NumbersUtils.format(best.profit)} profit), opening it.")
        GuiUtils.clickSlot(best.slot, GuiUtils.ButtonType.LEFT)
    }

    private fun buyHere(chest: ChestProfit.DungeonChest) {
        acted = true
        val fromCroesus = picked == chest
        if (! fromCroesus && ! (endOfRun.value && LocationUtils.world == WorldType.Catacombs && ! boughtThisRun)) return
        val menu = mc.player?.containerMenu ?: return
        val button = menu.slots.getOrNull(BUY_SLOT)?.item ?: return
        val lore = button.lore.map { it.removeFormatting() }
        if (lore.none { it.contains("Cost") }) return // already bought or not a reward page
        val price = cost(button.lore)
        if (price > maxCost.value * 1_000_000) return info("&e${chest.displayText} costs ${NumbersUtils.format(price)}, more than Max Cost.")
        if (chest.profit < minProfit.value * 1_000_000) return info("&7${chest.displayText}: ${NumbersUtils.format(chest.profit)} profit, below Min Profit - not buying.")
        GuiUtils.clickSlot(BUY_SLOT, GuiUtils.ButtonType.LEFT)
        picked = null
        if (LocationUtils.world == WorldType.Catacombs) boughtThisRun = true
        info("&aBought &d${chest.displayText} &7for ${NumbersUtils.format(price)} (${NumbersUtils.format(chest.profit)} profit).")
    }
}
//#endif
