package com.github.noamm9.ui.clickgui

import com.github.noamm9.NoammAddons.mc
import com.github.noamm9.compat.scanCodeCompat
import com.github.noamm9.config.ConfigManager
import com.github.noamm9.features.Feature
import com.github.noamm9.features.FeatureManager
import com.github.noamm9.features.IFeatureScreen
import com.github.noamm9.ui.clickgui.components.settings.Style
import com.github.noamm9.ui.clickgui.components.settings.Widget
import com.github.noamm9.ui.clickgui.components.settings.WidgetFactory
import com.github.noamm9.ui.clickgui.enums.CategoryType
import com.github.noamm9.ui.hud.HudEditorScreen
import com.github.noamm9.ui.notification.NotificationManager
import com.github.noamm9.ui.utils.MouseHelper
import com.github.noamm9.ui.utils.Resolution
import com.github.noamm9.ui.utils.ScrollState
import com.github.noamm9.ui.utils.TextInputHandler
import com.github.noamm9.utils.ColorUtils.withAlpha
import com.github.noamm9.utils.GuiUtils
import com.github.noamm9.utils.render.Render2D.drawBorder
import com.github.noamm9.utils.render.Render2D.drawCenteredString
import com.github.noamm9.utils.render.Render2D.drawRect
import com.github.noamm9.utils.render.Render2D.drawString
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import java.awt.Color

/**
 * SkyHanni / MoulConfig style settings: search on top, categories and features on the left,
 * every option as a card with its description underneath on the right.
 * Uses the same settings and widgets as the classic ClickGUI, so both always show the same values.
 */
class SkyHanniScreen: Screen(Component.literal("SkyBlock Lab")) {
    private companion object {
        const val MARGIN_X = 40f
        const val MARGIN_Y = 22f
        const val TITLE_H = 26f
        const val SIDEBAR_W = 150f
        const val ROW_H = 15f
        const val CARD_PAD = 5f
        const val CARD_GAP = 5f
        const val LINE_H = 10f

        val bg = Color(28, 28, 28, 245)
        val panel = Color(40, 40, 40, 255)
        val card = Color(44, 44, 44, 255)
        val cardHover = Color(52, 52, 52, 255)
        val field = Color(20, 20, 20, 255)
        val textDim = Color(150, 150, 150)
        val textFaint = Color(115, 115, 115)

        /** Remembered between openings, like SkyHanni does. */
        var lastCategory: CategoryType = CategoryType.DUNGEON
        var lastFeature: Feature? = null
    }

    private var query = ""
    private val search = TextInputHandler({ query }, { query = it; scroll.reset() })

    private var category = lastCategory
    private var selected: Feature? = lastFeature

    private val sideScroll = ScrollState(minThumbHeight = 18f)
    private val scroll = ScrollState(minThumbHeight = 18f)
    private val widgetCache = HashMap<Feature, List<Widget<*>>>()

    /** What the content area shows this frame, rebuilt every frame (cheap: only the visible feature). */
    private sealed class Entry {
        class Header(val text: String): Entry()
        class FeatureCard(val feature: Feature): Entry()
        class Option(val feature: Feature, val widget: Widget<*>, val prefix: String?): Entry()
    }

    private var entries = emptyList<Entry>()
    private val drawnFeatureCards = ArrayList<Pair<Feature, FloatArray>>() // feature -> x, y, w, h
    private val drawnSideRows = ArrayList<Pair<Any, FloatArray>>()
    private var visibleWidgets = emptyList<Widget<*>>()

    private fun winX() = MARGIN_X
    private fun winY() = MARGIN_Y
    private fun winW() = Resolution.width - MARGIN_X * 2
    private fun winH() = Resolution.height - MARGIN_Y * 2
    private fun contentX() = winX() + SIDEBAR_W + 8f
    private fun contentY() = winY() + TITLE_H + 6f
    private fun contentW() = winW() - SIDEBAR_W - 16f
    private fun contentH() = winH() - TITLE_H - 12f

    private fun widgetsOf(feature: Feature) = widgetCache.getOrPut(feature) {
        feature.configSettings.mapNotNull(WidgetFactory::fromSetting)
    }

    private fun featuresIn(cat: CategoryType) = FeatureManager.getFeaturesByCategory(cat).sortedBy { it.name.lowercase() }

    // ------------------------------------------------------------------ render

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        val mx = Resolution.getMouseX(mouseX.toDouble())
        val my = Resolution.getMouseY(mouseY.toDouble())
        Resolution.push(context)

        scroll.mouseDragged(my.toFloat())
        sideScroll.mouseDragged(my.toFloat())

        context.drawRect(0, 0, Resolution.width, Resolution.height, Color(0, 0, 0, 110))
        context.drawRect(winX(), winY(), winW(), winH(), bg)
        context.drawBorder(winX(), winY(), winW(), winH(), Color(255, 255, 255, 28))

        drawTitleBar(context, mx.toFloat(), my.toFloat())
        drawSidebar(context, mx.toFloat(), my.toFloat())
        drawContent(context, mx, my)

        TooltipManager.draw(context, Resolution.width, Resolution.height)
        Resolution.pop(context)
    }

    private fun drawTitleBar(ctx: GuiGraphicsExtractor, mx: Float, my: Float) {
        val x = winX()
        val y = winY()
        ctx.drawRect(x, y, winW(), TITLE_H, panel)
        ctx.drawRect(x, y + TITLE_H - 1f, winW(), 1f, Style.accentColor.withAlpha(160))
        ctx.drawString("§lSkyBlock Lab", x + 10f, y + 9f, Color.WHITE)

        val sw = 230f
        val sx = x + (winW() - sw) / 2f
        val sy = y + 4f
        ctx.drawRect(sx, sy, sw, 18f, field)
        ctx.drawRect(sx, sy + 17f, sw, 1f, if (search.listening) Style.accentColor else Color(255, 255, 255, 30))
        search.x = sx + 4f
        search.y = sy
        search.width = sw - 8f
        search.height = 18f
        if (query.isEmpty() && !search.listening) ctx.drawString("§8Search all settings... (Ctrl+F)", sx + 6f, sy + 5f, textFaint, shadow = false)
        else search.draw(ctx, mx, my)

        titleButtons().forEach { (label, rect) ->
            val hovered = inside(mx, my, rect)
            ctx.drawRect(rect[0], rect[1], rect[2], rect[3], if (hovered) cardHover else card)
            ctx.drawCenteredString(label, rect[0] + rect[2] / 2f, rect[1] + 5f, if (hovered) Style.accentColor else Color.WHITE)
        }
    }

    private fun titleButtons(): List<Pair<String, FloatArray>> {
        val y = winY() + 5f
        val right = winX() + winW() - 6f
        return listOf(
            "X" to floatArrayOf(right - 16f, y, 16f, 16f),
            "HUD" to floatArrayOf(right - 16f - 4f - 34f, y, 34f, 16f),
            "Classic" to floatArrayOf(right - 16f - 4f - 34f - 4f - 46f, y, 46f, 16f),
        )
    }

    private fun drawSidebar(ctx: GuiGraphicsExtractor, mx: Float, my: Float) {
        val x = winX() + 4f
        val top = winY() + TITLE_H + 6f
        val h = winH() - TITLE_H - 12f
        ctx.drawRect(x, top, SIDEBAR_W, h, panel)

        val rows = ArrayList<Pair<Any, String>>()
        for (cat in CategoryType.entries) {
            rows += cat to (if (cat == CategoryType.FLOOR7) "Floor 7" else cat.name.lowercase().replaceFirstChar { it.uppercase() })
            if (cat == category && query.isBlank()) featuresIn(cat).forEach { rows += it to it.name }
        }

        sideScroll.update(rows.size * ROW_H + 6f, h)
        drawnSideRows.clear()
        ctx.enableScissor(x.toInt(), top.toInt(), (x + SIDEBAR_W).toInt(), (top + h).toInt())
        var y = top + 3f - sideScroll.value
        for ((key, label) in rows) {
            val rect = floatArrayOf(x, y, SIDEBAR_W, ROW_H)
            drawnSideRows += key to rect
            val hovered = inside(mx, my, rect) && my in top .. top + h
            when (key) {
                is CategoryType -> {
                    val active = key == category && selected == null && query.isBlank()
                    if (active) ctx.drawRect(x, y, SIDEBAR_W, ROW_H, Style.accentColor.withAlpha(45))
                    else if (hovered) ctx.drawRect(x, y, SIDEBAR_W, ROW_H, Color(255, 255, 255, 12))
                    val color = if (key == category) Style.accentColor else Color.WHITE
                    ctx.drawString((if (key == category && query.isBlank()) "▾ " else "▸ ") + label, x + 6f, y + 4f, color)
                }

                is Feature -> {
                    val active = key == selected
                    if (active) ctx.drawRect(x, y, SIDEBAR_W, ROW_H, Style.accentColor.withAlpha(45))
                    else if (hovered) ctx.drawRect(x, y, SIDEBAR_W, ROW_H, Color(255, 255, 255, 12))
                    ctx.drawRect(x + 14f, y + 5f, 4f, 4f, if (key.enabled) Color(85, 200, 85) else Color(90, 90, 90))
                    ctx.drawString(fit(label, SIDEBAR_W - 30f), x + 22f, y + 4f, if (active) Style.accentColor else if (key.enabled) Color(220, 220, 220) else textDim)
                }
            }
            y += ROW_H
        }
        ctx.disableScissor()
        sideScroll.drawScrollbar(ctx, x + SIDEBAR_W - 2f, top, 2f, h, mx, my)
    }

    private fun buildEntries(): List<Entry> = buildList {
        val q = query.trim().lowercase()
        if (q.isNotEmpty()) {
            val all = FeatureManager.features.sortedBy { it.name.lowercase() }
            val featureHits = all.filter { it.name.lowercase().contains(q) || it.description.orEmpty().lowercase().contains(q) }
            if (featureHits.isNotEmpty()) {
                add(Entry.Header("Features"))
                featureHits.forEach { add(Entry.FeatureCard(it)) }
            }
            val optionHits = all.flatMap { f ->
                widgetsOf(f).filter {
                    it.config.visibility() && (it.name.lowercase().contains(q) || it.config.description.orEmpty().lowercase().contains(q))
                }.map { Entry.Option(f, it, f.name) }
            }
            if (optionHits.isNotEmpty()) {
                add(Entry.Header("Settings"))
                addAll(optionHits)
            }
            if (isEmpty()) add(Entry.Header("Nothing matches \"$query\""))
            return@buildList
        }

        val feature = selected
        if (feature == null) {
            add(Entry.Header(if (category == CategoryType.FLOOR7) "Floor 7" else category.name.lowercase().replaceFirstChar { it.uppercase() }))
            featuresIn(category).forEach { add(Entry.FeatureCard(it)) }
            return@buildList
        }

        add(Entry.FeatureCard(feature))
        var lastSection: String? = null
        for (w in widgetsOf(feature)) {
            if (!w.config.visibility()) continue
            val section = w.config.section
            if (section != null && section != lastSection) {
                add(Entry.Header(section))
                lastSection = section
            }
            add(Entry.Option(feature, w, null))
        }
    }

    private fun featureCardHeight(feature: Feature, w: Float): Float {
        val lines = wrap(feature.description.orEmpty().replace("\\n", " "), w - CARD_PAD * 2 - 60f).size.coerceAtLeast(0)
        return 18f + lines * LINE_H + CARD_PAD
    }

    private fun optionHeight(entry: Entry.Option, w: Float): Float {
        val lines = wrap(entry.widget.config.description.orEmpty(), w - CARD_PAD * 2).size
        return CARD_PAD + (if (entry.prefix != null) LINE_H else 0f) + entry.widget.height + (if (lines > 0) 3f + lines * LINE_H else 0f) + CARD_PAD
    }

    private fun drawContent(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val mx = mouseX.toFloat()
        val my = mouseY.toFloat()
        val x = contentX()
        val top = contentY()
        val w = contentW() - 6f
        val h = contentH()

        entries = buildEntries()
        val heights = entries.map {
            when (it) {
                is Entry.Header -> 16f
                is Entry.FeatureCard -> featureCardHeight(it.feature, w)
                is Entry.Option -> optionHeight(it, w)
            }
        }
        scroll.update(heights.sum() + entries.size * CARD_GAP, h)

        drawnFeatureCards.clear()
        val shown = ArrayList<Widget<*>>()
        val insideContent = mx in x .. x + w && my in top .. top + h

        ctx.enableScissor(x.toInt(), top.toInt(), (x + w).toInt(), (top + h).toInt())
        var y = top - scroll.value
        entries.forEachIndexed { i, entry ->
            val eh = heights[i]
            val visible = y + eh >= top && y <= top + h
            when (entry) {
                is Entry.Header -> if (visible) {
                    ctx.drawString("§l${entry.text}", x + 2f, y + 5f, Style.accentColor)
                    ctx.drawRect(x + 2f, y + 15f, w - 4f, 1f, Color(255, 255, 255, 25))
                }

                is Entry.FeatureCard -> {
                    val f = entry.feature
                    val rect = floatArrayOf(x, y, w, eh)
                    drawnFeatureCards += f to rect
                    if (visible) {
                        val hovered = insideContent && inside(mx, my, rect)
                        ctx.drawRect(x, y, w, eh, if (hovered && selected != f) cardHover else card)
                        ctx.drawRect(x, y, 2f, eh, if (f.enabled) Style.accentColor else Color(80, 80, 80))
                        ctx.drawString("§l${f.name}", x + CARD_PAD + 3f, y + 6f, Color.WHITE)
                        var ly = y + 18f
                        for (line in wrap(f.description.orEmpty().replace("\\n", " "), w - CARD_PAD * 2 - 60f)) {
                            ctx.drawString(line, x + CARD_PAD + 3f, ly, textDim, shadow = false)
                            ly += LINE_H
                        }
                        drawSwitch(ctx, x + w - 34f, y + 6f, f.enabled)
                        if (selected != f && hasSettings(f)) ctx.drawString("§8settings ›", x + w - 58f, y + eh - 12f, textFaint, shadow = false)
                    }
                }

                is Entry.Option -> {
                    val widget = entry.widget
                    if (visible) {
                        ctx.drawRect(x, y, w, eh, card)
                        var wy = y + CARD_PAD
                        if (entry.prefix != null) {
                            ctx.drawString("§8${entry.prefix} ›", x + CARD_PAD, wy + 1f, textFaint, shadow = false)
                            wy += LINE_H
                        }
                        widget.x = (x + CARD_PAD).toInt()
                        widget.y = wy.toInt()
                        widget.width = (w - CARD_PAD * 2).toInt()
                        widget.draw(ctx, mouseX, mouseY)
                        shown += widget
                        var ly = wy + widget.height + 3f
                        for (line in wrap(widget.config.description.orEmpty(), w - CARD_PAD * 2)) {
                            ctx.drawString(line, x + CARD_PAD + 2f, ly, textDim, shadow = false)
                            ly += LINE_H
                        }
                    }
                    else {
                        widget.y = -10000
                    }
                }
            }
            y += eh + CARD_GAP
        }
        ctx.disableScissor()
        visibleWidgets = shown
        scroll.drawScrollbar(ctx, x + w + 2f, top, 2f, h, mx, my)
    }

    private fun drawSwitch(ctx: GuiGraphicsExtractor, x: Float, y: Float, on: Boolean) {
        ctx.drawRect(x, y, 26f, 11f, if (on) Style.accentColor.withAlpha(150) else Color(70, 70, 70))
        ctx.drawRect(if (on) x + 16f else x, y, 10f, 11f, if (on) Style.accentColor else Color(160, 160, 160))
    }

    private fun hasSettings(f: Feature) = f is IFeatureScreen || f.configSettings.isNotEmpty()

    // ------------------------------------------------------------------ input

    override fun mouseClicked(mouseButtonEvent: MouseButtonEvent, bl: Boolean): Boolean {
        val mx = Resolution.getMouseX(mouseButtonEvent.x).toFloat()
        val my = Resolution.getMouseY(mouseButtonEvent.y).toFloat()
        val button = mouseButtonEvent.button()
        val left = button == InputConstants.MOUSE_BUTTON_LEFT

        if (search.mouseClicked(mx, my, mouseButtonEvent)) return true
        search.listening = false

        if (left) titleButtons().forEach { (label, rect) ->
            if (!inside(mx, my, rect)) return@forEach
            Style.playClickSound(1f)
            when (label) {
                "X" -> onClose()
                "HUD" -> { saveAndClear(); GuiUtils.setScreen(HudEditorScreen()) }
                "Classic" -> { saveAndClear(); GuiUtils.setScreen(ClickGuiScreen()) }
            }
            return true
        }

        if (left && sideScroll.mouseClicked(mx, my)) return true
        if (left && scroll.mouseClicked(mx, my)) return true

        val sideTop = winY() + TITLE_H + 6f
        val sideBottom = winY() + winH() - 6f
        if (my in sideTop .. sideBottom) drawnSideRows.firstOrNull { inside(mx, my, it.second) }?.let { (key, _) ->
            if (!left) return true
            Style.playClickSound(1f)
            when (key) {
                is CategoryType -> { category = key; selected = null; query = "" }
                is Feature -> { category = key.category; selected = key; query = "" }
            }
            scroll.reset()
            remember()
            return true
        }

        val x = contentX()
        val top = contentY()
        if (mx !in x .. x + contentW() || my !in top .. top + contentH()) return super.mouseClicked(mouseButtonEvent, bl)

        visibleWidgets.forEach { if (it.mouseClicked(mx.toDouble(), my.toDouble(), button)) return true }

        drawnFeatureCards.firstOrNull { inside(mx, my, it.second) }?.let { (f, rect) ->
            val onSwitch = mx >= rect[0] + rect[2] - 40f && my <= rect[1] + 20f
            if (left && (onSwitch || selected == f)) {
                f.toggle()
                Style.playClickSound(if (f.enabled) 1.1f else 0.8f)
            }
            else if (left || button == InputConstants.MOUSE_BUTTON_RIGHT) {
                openFeature(f)
            }
            return true
        }
        return true
    }

    private fun openFeature(f: Feature) {
        if (f is IFeatureScreen) {
            if (f.enabled) GuiUtils.setScreen(f.createScreen())
            else NotificationManager.push("SkyBlock Lab", "&fEnable &b${f.name} &ffirst to open its settings!")
            return
        }
        Style.playClickSound(1f)
        query = ""
        category = f.category
        selected = f
        scroll.reset()
        remember()
    }

    override fun mouseReleased(mouseButtonEvent: MouseButtonEvent): Boolean {
        search.mouseReleased()
        scroll.mouseReleased()
        sideScroll.mouseReleased()
        visibleWidgets.forEach { it.mouseReleased(mouseButtonEvent.button()) }
        return super.mouseReleased(mouseButtonEvent)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontal: Double, vertical: Double): Boolean {
        val mx = Resolution.getMouseX(mouseX)
        val my = Resolution.getMouseY(mouseY)
        visibleWidgets.forEach { if (it.mouseScrolled(mx, my, vertical)) return true }
        if (mx < contentX()) sideScroll.scroll((vertical * 30).toFloat())
        else scroll.scroll((vertical * 30).toFloat())
        return true
    }

    override fun charTyped(characterEvent: CharacterEvent): Boolean {
        visibleWidgets.forEach { if (it.charTyped(characterEvent.codepoint.toChar())) return true }
        if (search.keyTyped(characterEvent)) return true
        return super.charTyped(characterEvent)
    }

    override fun keyPressed(keyEvent: KeyEvent): Boolean {
        visibleWidgets.forEach { if (it.keyPressed(keyEvent.key, keyEvent.scanCodeCompat, keyEvent.modifiers)) return true }
        if (search.keyPressed(keyEvent)) return true
        if (keyEvent.hasControlDown() && keyEvent.input() == InputConstants.KEY_F) {
            search.listening = !search.listening
            return true
        }
        if (keyEvent.key == InputConstants.KEY_ESCAPE && selected != null && query.isBlank()) {
            selected = null
            scroll.reset()
            remember()
            return true
        }
        return super.keyPressed(keyEvent)
    }

    private fun remember() {
        lastCategory = category
        lastFeature = selected
    }

    private fun saveAndClear() {
        widgetCache.values.flatten().forEach { it.clearFocus() }
        search.listening = false
        MouseHelper.resetCursor()
        ConfigManager.save()
    }

    override fun onClose() {
        saveAndClear()
        super.onClose()
    }

    // ------------------------------------------------------------------ text helpers

    private fun inside(mx: Float, my: Float, r: FloatArray) = mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3]

    private fun textWidth(s: String) = mc.font.width(s.replace(Regex("[§&][0-9a-fk-or]"), ""))

    private fun fit(s: String, max: Float): String {
        if (textWidth(s) <= max) return s
        var out = s
        while (out.isNotEmpty() && textWidth("$out…") > max) out = out.dropLast(1)
        return "$out…"
    }

    private val wrapCache = HashMap<Pair<String, Int>, List<String>>()

    private fun wrap(text: String, max: Float): List<String> {
        if (text.isBlank()) return emptyList()
        return wrapCache.getOrPut(text to max.toInt()) {
            val lines = ArrayList<String>()
            for (paragraph in text.split('\n')) {
                var line = ""
                for (word in paragraph.split(' ')) {
                    val candidate = if (line.isEmpty()) word else "$line $word"
                    if (textWidth(candidate) > max && line.isNotEmpty()) {
                        lines += line
                        line = word
                    }
                    else line = candidate
                }
                if (line.isNotEmpty()) lines += line
            }
            lines
        }
    }
}
