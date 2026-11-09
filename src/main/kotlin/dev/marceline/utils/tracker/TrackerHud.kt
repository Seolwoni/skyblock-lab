package dev.marceline.utils.tracker

import dev.marceline.utils.NumbersUtils
import dev.marceline.utils.render.Render2D.drawString
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
        if (! o.countsOnly) lines += "§cProfit" to short(total)
        val hours = Uptime.perHour(total, b.uptimeMs)
        val rateBase = o.rateCounter?.let { Uptime.perHour(b.counter(it).toDouble(), b.uptimeMs) }
        if (b.uptimeMs > 0) {
            lines += "§7Time" to NumbersUtils.formatTime(b.uptimeMs)
            if (! o.countsOnly && hours > 0) lines += "§7Per hour" to short(hours)
            if (rateBase != null && rateBase > 0) lines += "§7${o.rateCounter?.replaceFirstChar { it.uppercase() }} / h" to NumbersUtils.formatComma(rateBase.toLong())
        }

        val gap = 10f
        val leftW = lines.maxOf { it.first.width().toFloat() }
        val rightW = lines.maxOf { it.second.width().toFloat() }
        val w = leftW + (if (rightW > 0) gap + rightW else 0f)
        var y = 0f
        lines.forEachIndexed { i, (l, r) ->
            val first = i == 0
            ctx.drawString(l, 0f, y, if (first) title else if (l.startsWith("§c")) profit else text, shadow = false)
            if (r.isNotEmpty()) ctx.drawString(r, w - r.width(), y, if (l.startsWith("§c")) profit else if (l.startsWith("§7")) dim else value, shadow = false)
            y += if (first) 11f else 10f
        }
        return w to y
    }

    private fun sample() = TrackerBucket().apply {
        add("MAGMA_FISH", 1_284); add("ENCHANTED_SULPHUR", 42); add(ItemTracker.COINS, 152_000)
        count("catches", 312); uptimeMs = 1_920_000
    }
}
