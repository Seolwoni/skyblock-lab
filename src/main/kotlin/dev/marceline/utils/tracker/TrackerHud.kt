package dev.marceline.utils.tracker

import dev.marceline.utils.NumbersUtils
import dev.marceline.utils.render.RenderHelper.width
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Color

/**
 * How a tracker reads on screen, in the Marceline look: a gold title with the span, the items by value (name and
 * count on the left, coins on the right), then the counters and the totals with the per-hour rate.
 */
object TrackerHud {
    private val title = Color(201, 164, 106)
    private val text = Color(236, 226, 214)
    private val dim = Color(176, 160, 150)
    private val value = Color(232, 196, 140)
    private val profit = Color(232, 80, 106)

    /** One extra line under the items: a label and what it shows. */
    data class Extra(val label: String, val shown: String)

    class Options(
        val span: TrackerSpan,
        val prices: PriceSource,
        val maxLines: Int = 12,
        /** Items worth less than this (all together) are left out. */
        val hideBelow: Double = 0.0,
        /** Shown with their count only (sea creatures, kills): no coin column. */
        val countsOnly: Boolean = false,
        /** What the per-hour line counts ("catches", "kills"); null for none. */
        val rateCounter: String? = null,
    )

    /**
     * The game's colour codes in the Marceline palette for tracker text: soft versions of the rarity colours
     * (uncommon sage, rare dusk blue, epic plum, legendary gold, mythic rose), ivory and warm greys for the rest.
     */
    private fun palette(c: Char): Color? = when (c.lowercaseChar()) {
        'f' -> text
        '7' -> dim
        '8' -> Color(128, 110, 106)
        'a', '2' -> Color(158, 196, 160)
        '9', '1', 'b', '3' -> Color(146, 166, 210)
        '5' -> Color(182, 132, 196)
        '6', 'e' -> title
        'd' -> profit
        'c', '4' -> Color(226, 104, 112)
        else -> null
    }

    private fun drawText(ctx: GuiGraphicsExtractor, s: String, x: Float, y: Float, base: Color) {
        dev.marceline.ui.clickgui.components.settings.Style.drawPaletteString(ctx, s, x, y, base, { it.width().toFloat() }, 255, ::palette)
    }

    private fun short(n: Double) = NumbersUtils.format(n.toLong())

    /** Draws [t] and returns its size. [extras] go under the items; [example] shows sample numbers (HUD editor). */
    fun draw(ctx: GuiGraphicsExtractor, t: ItemTracker, o: Options, extras: List<Extra> = emptyList(), example: Boolean = false): Pair<Float, Float> {
        val b = if (example) sample() else t.bucket(o.span)
        val rows = b.items.entries.filter { ! it.value.hidden }
            .map { (id, it) -> Triple(id, it.amount, TrackerPrices.each(id, o.prices) * it.amount) }
            .filter { o.countsOnly || it.third >= o.hideBelow || it.first == ItemTracker.COINS }
            .sortedByDescending { if (o.countsOnly) it.second.toDouble() else it.third }

        val lines = ArrayList<Pair<String, String>>() // left, right
        lines += "${t.title}  §7${o.span.title}" to ""
        val shown = rows.take(o.maxLines)
        for ((id, amount, coins) in shown) {
            val name = t.namer?.invoke(id) ?: TrackerPrices.name(id)
            val left = if (id == ItemTracker.COINS) "§f$name" else "§f$name §7×${NumbersUtils.formatComma(amount)}"
            lines += left to (if (o.countsOnly) "" else short(coins))
        }
        if (rows.size > shown.size) lines += "§8+${rows.size - shown.size} more" to ""
        for (e in extras) lines += "§7${e.label}" to e.shown
        val total = rows.sumOf { it.third }
        if (! o.countsOnly) lines += "§dProfit" to short(total)
        // the all-time figure under any shorter span, so both are in view
        if (o.span != TrackerSpan.TOTAL && ! example) {
            val all = t.bucket(TrackerSpan.TOTAL)
            val allValue = if (o.countsOnly) NumbersUtils.formatComma(all.items.values.sumOf { it.amount })
                else short(all.items.entries.sumOf { (id, it) -> TrackerPrices.each(id, o.prices) * it.amount })
            lines += "§7All time" to allValue
        }
        val hours = Uptime.perHour(total, b.uptimeMs)
        val rateBase = o.rateCounter?.let { Uptime.perHour(b.counter(it).toDouble(), b.uptimeMs) }
        if (b.uptimeMs > 0) {
            lines += "§7Time" to NumbersUtils.formatTime(b.uptimeMs)
            if (! o.countsOnly && hours > 0) lines += "§7Per hour" to short(hours)
            if (rateBase != null && rateBase > 0) lines += "§7${o.rateCounter.replaceFirstChar { it.uppercase() }} / h" to NumbersUtils.formatComma(rateBase.toLong())
        }

        val gap = 10f
        val leftW = lines.maxOf { it.first.width().toFloat() }
        val rightW = lines.maxOf { it.second.width().toFloat() }
        val w = leftW + (if (rightW > 0) gap + rightW else 0f)
        var y = 0f
        lines.forEachIndexed { i, (l, r) ->
            val first = i == 0
            drawText(ctx, l, 0f, y, if (first) title else if (l.startsWith("§d")) profit else text)
            if (r.isNotEmpty()) drawText(ctx, r, w - r.width(), y, if (l.startsWith("§d")) profit else if (l.startsWith("§7")) dim else value)
            y += if (first) 11f else 10f
        }
        return w to y
    }

    private fun sample() = TrackerBucket().apply {
        add("MAGMA_FISH", 1_284); add("ENCHANTED_SULPHUR", 42); add(ItemTracker.COINS, 152_000)
        count("catches", 312); uptimeMs = 1_920_000
    }
}
