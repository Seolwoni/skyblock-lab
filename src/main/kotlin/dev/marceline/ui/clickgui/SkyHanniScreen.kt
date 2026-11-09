package dev.marceline.ui.clickgui

import dev.marceline.Marceline.mc
import dev.marceline.compat.scanCodeCompat
import dev.marceline.config.ConfigManager
import dev.marceline.features.Feature
import dev.marceline.features.FeatureManager
import dev.marceline.features.IFeatureScreen
import dev.marceline.ui.clickgui.components.settings.Style
import dev.marceline.ui.clickgui.components.settings.Widget
import dev.marceline.ui.clickgui.components.settings.WidgetFactory
import dev.marceline.ui.clickgui.enums.CategoryType
import dev.marceline.ui.hud.HudEditorScreen
import dev.marceline.ui.notification.NotificationManager
import dev.marceline.ui.utils.Animation
import dev.marceline.ui.utils.MouseHelper
import dev.marceline.ui.utils.Resolution
import dev.marceline.ui.utils.ScrollState
import dev.marceline.ui.utils.TextInputHandler
import dev.marceline.utils.render.UiFont
import dev.marceline.utils.ColorUtils.withAlpha
import dev.marceline.utils.GuiUtils
import dev.marceline.utils.render.Render2D.drawBorder
import dev.marceline.utils.render.Render2D.drawCenteredString
import dev.marceline.utils.render.Render2D.drawHorizontalGradient
import dev.marceline.utils.render.Render2D.drawRect
import dev.marceline.utils.render.Render2D.drawVerticalGradient
import dev.marceline.utils.render.Render2D.drawString
import dev.marceline.utils.render.Render2D.drawTexture
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import java.awt.Color
import dev.marceline.ui.clickgui.SkyHanniTheme.CARD_GAP
import dev.marceline.ui.clickgui.SkyHanniTheme.CARD_PAD
import dev.marceline.ui.clickgui.SkyHanniTheme.DESC_SCALE
import dev.marceline.ui.clickgui.SkyHanniTheme.LINE_H
import dev.marceline.ui.clickgui.SkyHanniTheme.line
import dev.marceline.ui.clickgui.SkyHanniTheme.MARGIN_X
import dev.marceline.ui.clickgui.SkyHanniTheme.MARGIN_Y
import dev.marceline.ui.clickgui.SkyHanniTheme.ROW_H
import dev.marceline.ui.clickgui.SkyHanniTheme.SIDEBAR_W
import dev.marceline.ui.clickgui.SkyHanniTheme.TITLE_H
import dev.marceline.ui.clickgui.SkyHanniTheme.bg
import dev.marceline.ui.clickgui.SkyHanniTheme.card
import dev.marceline.ui.clickgui.SkyHanniTheme.cardHover
import dev.marceline.ui.clickgui.SkyHanniTheme.cardOff
import dev.marceline.ui.clickgui.SkyHanniTheme.field
import dev.marceline.ui.clickgui.SkyHanniTheme.fit
import dev.marceline.ui.clickgui.SkyHanniTheme.glass
import dev.marceline.ui.clickgui.SkyHanniTheme.glassA
import dev.marceline.ui.clickgui.SkyHanniTheme.icons
import dev.marceline.ui.clickgui.SkyHanniTheme.inside
import dev.marceline.ui.clickgui.SkyHanniTheme.logoFrom
import dev.marceline.ui.clickgui.SkyHanniTheme.logoTo
import dev.marceline.ui.clickgui.SkyHanniTheme.offDot
import dev.marceline.ui.clickgui.SkyHanniTheme.onDot
import dev.marceline.ui.clickgui.SkyHanniTheme.panel
import dev.marceline.ui.clickgui.SkyHanniTheme.panelTop
import dev.marceline.ui.clickgui.SkyHanniTheme.textDim
import dev.marceline.ui.clickgui.SkyHanniTheme.textFaint
import dev.marceline.ui.clickgui.SkyHanniTheme.textMain
import dev.marceline.ui.clickgui.SkyHanniTheme.textSoft
import dev.marceline.ui.clickgui.SkyHanniTheme.textWidth
import dev.marceline.ui.clickgui.SkyHanniTheme.titleOf
import dev.marceline.ui.clickgui.SkyHanniTheme.uiWidth
import dev.marceline.ui.clickgui.SkyHanniTheme.wrap
import dev.marceline.ui.clickgui.SkyHanniTheme.wrapCache

/**
 * SkyHanni / MoulConfig style settings: search on top, categories and features on the left,
 * every option as a card with its description underneath on the right.
 * Uses the same settings and widgets as the classic ClickGUI, so both always show the same values.
 */
class SkyHanniScreen: Screen(Component.literal("Marceline")) {
    companion object {
        /** Draws a category icon [size] GUI pixels wide at x, y, tinted with [color]. */
        fun drawCategoryIcon(ctx: GuiGraphicsExtractor, cat: CategoryType, x: Float, y: Float, size: Float, color: Color) =
            SkyHanniTheme.drawCategoryIcon(ctx, cat, x, y, size, color)

        /** The logo intro has played this launch. */
        private var introPlayed = false
        /** Tests: hold the intro at this point (0..1), -1 = run normally. */
        @JvmField var introFreeze = - 1f

        /** Remembered between openings, like SkyHanni does. */
        @JvmField var lastCategory: CategoryType = CategoryType.DUNGEON
        private val GROUP_ORDER = listOf("", "Core", "Floor 7", "Routes")
        @JvmField var lastFeature: Feature? = null
    }

    private val openAnim = Animation(170, 0f)
    private val introOn = when (dev.marceline.features.impl.dev.ClickGui.intro.value) {
        0 -> false
        1 -> ! introPlayed
        else -> true
    }.also { if (it) introPlayed = true }
    private var introStart = 0L
    private var introSkipped = false
    private val INTRO_MS = 1300L

    /** 0..1 through the intro, 1 when it's over (or off / skipped). */
    private fun introT(): Float {
        if (introFreeze >= 0f) return introFreeze
        if (! introOn || introSkipped) return 1f
        if (introStart == 0L) introStart = System.currentTimeMillis()
        return ((System.currentTimeMillis() - introStart) / INTRO_MS.toFloat()).coerceIn(0f, 1f)
    }
    private val contentAnim = Animation(200, 1f)
    private var closing = false
    private val anims = HashMap<Any, Animation>()

    private var query = ""
    private val search = TextInputHandler({ query }, { query = it; scroll.reset() })

    private var category = lastCategory
    private var selected: Feature? = lastFeature

    private val sideScroll = ScrollState(260L, minThumbHeight = 18f)
    private val scroll = ScrollState(260L, minThumbHeight = 18f)
    private val widgetCache = HashMap<Feature, List<Widget<*>>>()

    /** What the content area shows this frame, rebuilt every frame (cheap: only the visible feature). */
    private sealed class Entry {
        class Header(val text: String): Entry()
        class FeatureCard(val feature: Feature): Entry()
        class Option(val feature: Feature, val widget: Widget<*>, val prefix: String?): Entry()
        /** The section tabs of a feature with many settings. */
        class Tabs(val feature: Feature, val tabs: List<String>, val current: String): Entry()
    }

    private val RAIL_W = 104f
    private val RAIL_ROW = 17f
    private val tabOf = HashMap<Feature, String>()
    private val drawnTabs = ArrayList<Pair<String, FloatArray>>()

    /** Settings grouped by section, in order ("General" for the ones before the first section). */
    private fun sectionsOf(feature: Feature): List<Pair<String, List<Widget<*>>>> {
        val out = LinkedHashMap<String, MutableList<Widget<*>>>()
        var cur = "General"
        for (w in widgetsOf(feature)) {
            w.config.section?.let { cur = it }
            out.getOrPut(cur) { ArrayList() } += w
        }
        return out.entries.map { it.key to it.value }.filter { (_, ws) -> ws.any { it.config.visibility() } }
    }

    private fun tabLayout(tabs: List<String>, w: Float): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        var tx = 0f
        var ty = 0f
        for (t in tabs) {
            val tw = uiWidth(t) + 18f
            if (tx > 0f && tx + tw > w) { tx = 0f; ty += 20f }
            out += floatArrayOf(tx, ty, tw, 16f)
            tx += tw + 4f
        }
        return out
    }

    private var entries = emptyList<Entry>()

    /** A group heading in the sidebar (Dungeons: Core / Floor 7 / Routes); clicking scrolls to it. */
    private data class GroupRow(val name: String)

    /** Group heading to scroll to on the next frame. */
    private var jumpTo: String? = null
    private val drawnFeatureCards = ArrayList<Pair<Feature, FloatArray>>() // feature -> x, y, w, h
    private val drawnSideRows = ArrayList<Pair<Any, FloatArray>>()
    private val drawnOptionCards = ArrayList<FloatArray>()

    // ------------------------------------------------------------------ click feel
    private class Ripple(val rect: FloatArray, val x: Float, val y: Float, val start: Long)
    private val ripples = ArrayList<Ripple>()
    private val presses = HashMap<Any, Long>()

    private fun ripple(rect: FloatArray, x: Float, y: Float, key: Any? = null) {
        if (! dev.marceline.features.impl.dev.ClickGui.clickFeel.value) return
        ripples += Ripple(rect.copyOf(), x, y, System.currentTimeMillis())
        if (key != null) presses[key] = System.currentTimeMillis()
    }

    /** Pushes a matrix that dips [depth] (0.012 = 1.2%) around [rect]'s centre for a moment after a click. Always pop after. */
    private fun pressPush(ctx: GuiGraphicsExtractor, key: Any, rect: FloatArray, depth: Float) {
        ctx.pose().pushMatrix()
        val t0 = presses[key] ?: return
        val t = (System.currentTimeMillis() - t0) / 220f
        if (t >= 1f) { presses.remove(key); return }
        val s = 1f - depth * kotlin.math.sin(t * Math.PI).toFloat()
        val cx = rect[0] + rect[2] / 2f
        val cy = rect[1] + rect[3] / 2f
        ctx.pose().translate(cx, cy)
        ctx.pose().scale(s, s)
        ctx.pose().translate(- cx, - cy)
    }

    /** A soft light circle spreading from the click point, kept inside what was clicked. */
    private fun drawRipples(ctx: GuiGraphicsExtractor) {
        if (ripples.isEmpty()) return
        val now = System.currentTimeMillis()
        ripples.removeIf { now - it.start > 520 }
        for (r in ripples) {
            val t = (now - r.start) / 520f
            val ease = 1f - (1f - t) * (1f - t) * (1f - t)
            val far = maxOf(r.rect[2], r.rect[3])
            val rad = 4f + far * 1.05f * ease
            val alpha = ((1f - t) * 34).toInt()
            ctx.enableScissor(r.rect[0].toInt(), r.rect[1].toInt(), (r.rect[0] + r.rect[2]).toInt(), (r.rect[1] + r.rect[3]).toInt())
            Style.roundRect(ctx, r.x - rad, r.y - rad, rad * 2, rad * 2, rad, Style.mix(Color.WHITE, Style.accentColor, 0.35f).withAlpha(alpha))
            ctx.disableScissor()
        }
    }

    /** Light on glass: a faint highlight along the top edge and a sheen fading down from it. */
    private fun drawSheen(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float) {
        val g = glass
        ctx.drawVerticalGradient(x + 5f, y + 1f, w - 10f, 46f, Color(255, 255, 255, (10 + 10 * g).toInt()), Color(255, 255, 255, 0))
        ctx.drawHorizontalGradient(x + 8f, y + 0.5f, (w - 16f) / 2f, 0.75f, Color(255, 255, 255, 0), Color(255, 255, 255, (40 + 25 * g).toInt()))
        ctx.drawHorizontalGradient(x + w / 2f, y + 0.5f, (w - 16f) / 2f, 0.75f, Color(255, 255, 255, (40 + 25 * g).toInt()), Color(255, 255, 255, 0))
        // faint accent rim on the sides, brighter towards the top
        ctx.drawVerticalGradient(x, y + 6f, 0.75f, h * 0.6f, Style.accentColor.withAlpha((50 * (0.4f + g)).toInt().coerceAtMost(90)), Style.accentColor.withAlpha(0))
        ctx.drawVerticalGradient(x + w - 0.75f, y + 6f, 0.75f, h * 0.6f, Style.accent2.withAlpha((50 * (0.4f + g)).toInt().coerceAtMost(90)), Style.accent2.withAlpha(0))
    }
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

    private fun featuresIn(cat: CategoryType) = FeatureManager.getFeaturesByCategory(cat)
        .sortedWith(compareBy<Feature>({ GROUP_ORDER.indexOf(it.group) }, { it.name.lowercase() }))

    /** Tabs that have something in them; Mining / Farming stay hidden until features land there. */
    private fun shownCategories() = CategoryType.entries.filter { it == category || featuresIn(it).isNotEmpty() }

    // ------------------------------------------------------------------ render

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        UiFont.active = dev.marceline.features.impl.dev.ClickGui.customFont.value
        try {
            render(context, mouseX, mouseY)
        } finally {
            UiFont.active = false
        }
    }

    private fun render(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val mx = Resolution.getMouseX(mouseX.toDouble())
        val my = Resolution.getMouseY(mouseY.toDouble())
        Resolution.push(context)

        scroll.mouseDragged(my.toFloat())
        sideScroll.mouseDragged(my.toFloat())

        val intro = introT()
        if (intro < 1f) drawIntro(context, intro)
        if (intro < 0.7f) {
            Resolution.pop(context)
            return
        }
        openAnim.update(if (closing) 0f else 1f)
        contentAnim.update(1f)
        val a = openAnim.value
        if (closing && a <= 0.02f) {
            Resolution.pop(context)
            closing = false
            super.onClose()
            return
        }

        if (intro >= 1f) context.drawRect(0, 0, Resolution.width, Resolution.height, Color(6, 2, 14, (130 * a).toInt()))

        // opens by growing from 96% while fading in, closes the other way
        val cx = winX() + winW() / 2f
        val cy = winY() + winH() / 2f
        val sc = 0.96f + 0.04f * a
        context.pose().pushMatrix()
        context.pose().translate(cx, cy)
        context.pose().scale(sc, sc)
        context.pose().translate(- cx, - cy)

        Style.drawShadow(context, winX(), winY(), winW(), winH(), 5f, a)
        Style.roundRect(context, winX(), winY(), winW(), winH(), 5f, bg)
        drawSheen(context, winX(), winY(), winW(), winH())

        drawTitleBar(context, mx.toFloat(), my.toFloat())
        drawSidebar(context, mx.toFloat(), my.toFloat())
        drawContent(context, mx, my)

        drawRipples(context)
        if (a < 0.999f) Style.roundRect(context, winX(), winY(), winW(), winH(), 5f, Color(15, 12, 13, (255 * (1f - a)).toInt()))
        context.pose().popMatrix()

        if (! closing) TooltipManager.draw(context, Resolution.width, Resolution.height)
        Resolution.pop(context)
    }

    private fun drawTitleBar(ctx: GuiGraphicsExtractor, mx: Float, my: Float) {
        val x = winX()
        val y = winY()
        Style.roundRect(ctx, x, y, winW(), TITLE_H, 5f, panelTop)
        ctx.drawVerticalGradient(x, y + 5f, winW(), TITLE_H - 5f, panelTop, panel)
        ctx.drawHorizontalGradient(x, y + TITLE_H - 1f, winW() / 2f, 1f, Style.accentColor.withAlpha(0), Style.accentColor)
        ctx.drawHorizontalGradient(x + winW() / 2f, y + TITLE_H - 1f, winW() / 2f, 1f, Style.accent2, Style.accent2.withAlpha(0))
        SkyHanniTheme.drawLogoMark(ctx, x + 8f, y + 5.5f, 15f, Color.WHITE)
        val logoEnd = drawLogo(ctx, x + 26f, y + 9f)
        ctx.drawString(dev.marceline.Marceline.MOD_VERSION, logoEnd + 6f, y + 9f, textFaint, shadow = false)

        val sw = 240f
        val sx = x + (winW() - sw) / 2f
        val sy = y + 4f
        val focus = anim("searchFocus", 180).also { it.update(if (search.listening) 1f else 0f) }.value
        if (focus > 0.01f) {
            Style.roundRect(ctx, sx - 3f, sy - 3f, sw + 6f, 24f, 6f, Style.accentColor.withAlpha((30 * focus).toInt()))
            Style.roundRect(ctx, sx - 1f, sy - 1f, sw + 2f, 20f, 4f, Style.mix(Style.accentColor, Style.accent2, 0.5f).withAlpha((200 * focus).toInt()))
        }
        Style.roundRect(ctx, sx, sy, sw, 18f, 3f, field)
        if (query.isNotEmpty()) {
            val hits = entries.count { it is Entry.FeatureCard || it is Entry.Option }
            val label = "$hits"
            ctx.drawString(label, sx + sw - 7f - uiWidth(label), sy + 5f, if (hits > 0) accentLight() else Color(232, 80, 106), shadow = false)
        }
        search.x = sx + 6f
        search.y = sy
        search.width = sw - 12f
        search.height = 18f
        if (query.isEmpty() && !search.listening) ctx.drawString("Search ${FeatureManager.features.size} features  (Ctrl+F)", sx + 7f, sy + 5f, textFaint, shadow = false)
        else search.draw(ctx, mx, my)

        titleButtons().forEach { (label, rect) ->
            val hovered = inside(mx, my, rect)
            val warn = label == "All On" // everything is off right now
            pressPush(ctx, "btn:$label", rect, 0.06f)
            Style.roundRect(ctx, rect[0], rect[1], rect[2], rect[3], 3f, if (warn) Color(120, 40, 50) else if (hovered) cardHover else Color(42, 38, 52, glassA(255, 80)))
            ctx.drawCenteredString(label, rect[0] + rect[2] / 2f, rect[1] + 5f, if (warn) Color.WHITE else if (hovered) Style.accentColor else textSoft)
            ctx.pose().popMatrix()
        }
    }

    private fun titleButtons(): List<Pair<String, FloatArray>> {
        val y = winY() + 5f
        val right = winX() + winW() - 6f
        return listOf(
            "✕" to floatArrayOf(right - 16f, y, 16f, 16f),
            "HUD" to floatArrayOf(right - 16f - 4f - 34f, y, 34f, 16f),
            "Classic" to floatArrayOf(right - 16f - 4f - 34f - 4f - 46f, y, 46f, 16f),
            (if (dev.marceline.features.MasterSwitch.allOff) "All On" else "All Off") to floatArrayOf(right - 16f - 4f - 34f - 4f - 46f - 4f - 48f, y, 48f, 16f),
        )
    }

    private fun drawSidebar(ctx: GuiGraphicsExtractor, mx: Float, my: Float) {
        val x = winX() + 4f
        val top = winY() + TITLE_H + 6f
        val h = winH() - TITLE_H - 12f
        Style.roundRect(ctx, x, top, SIDEBAR_W, h, 4f, panel)

        val rows = ArrayList<Pair<Any, String>>()
        for (cat in shownCategories()) {
            rows += cat to titleOf(cat)
            // the open tab shows its groups (Dungeons: Core / Floor 7 / Routes), not every feature again
            if (cat == category && query.isBlank()) {
                val groups = featuresIn(cat).map { it.group }.distinct().filter { it.isNotBlank() }
                if (groups.size > 1) groups.forEach { rows += GroupRow(it) to it }
            }
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
                    val open = key == category && query.isBlank()
                    val active = open && selected == null
                    val sel = anim(key to "sel", 220).also { it.update(if (open) (if (active) 1f else 0.6f) else 0f) }.value
                    val hov = anim(key, 140).also { it.update(if (hovered && ! open) 1f else 0f) }.value
                    if (sel > 0.01f) {
                        ctx.drawHorizontalGradient(x, y, SIDEBAR_W, ROW_H, Style.accentColor.withAlpha((70 * sel).toInt()), Style.accent2.withAlpha((18 * sel).toInt()))
                        ctx.drawRect(x, y + (ROW_H * (1 - sel)) / 2f, 2f, ROW_H * sel, Style.accentColor)
                    }
                    if (hov > 0.01f) ctx.drawRect(x, y, SIDEBAR_W, ROW_H, Color(255, 255, 255, (12 * hov).toInt()))
                    if (key != rows.first().first) ctx.drawRect(x + 6f, y, SIDEBAR_W - 12f, 1f, line)
                    drawIcon(ctx, key, x + 8f, y + 3.5f, if (open) 1f else 0.55f + 0.45f * hov)
                    ctx.drawString(label, x + 21f, y + 4f, if (open) accentLight() else textMain)
                    val all = featuresIn(key)
                    val count = if (open) "${all.count { it.enabled }}/${all.size}" else "${all.size}"
                    if (open) Style.drawTabular(ctx, count, x + SIDEBAR_W - 8f - Style.tabWidth(count), y + 4f, accentLight())
                    else ctx.drawString(count, x + SIDEBAR_W - 8f - uiWidth(count), y + 4f, textFaint, shadow = false)
                }

                is GroupRow -> {
                    val list = featuresIn(category).filter { it.group == key.name }
                    val hov = anim(key to "side", 140).also { it.update(if (hovered) 1f else 0f) }.value
                    if (hov > 0.01f) ctx.drawRect(x + 6f, y, SIDEBAR_W - 6f, ROW_H, Color(255, 255, 255, (10 * hov).toInt()))
                    ctx.drawRect(x + 14f, y + 3f, 1f, ROW_H - 6f, Style.accentColor.withAlpha(110))
                    ctx.drawString(fit(label, SIDEBAR_W - 50f), x + 21f, y + 4f, if (hov > 0.5f) textMain else textSoft, shadow = false)
                    val count = "${list.count { it.enabled }}/${list.size}"
                    ctx.drawString(count, x + SIDEBAR_W - 8f - uiWidth(count), y + 4f, textFaint, shadow = false)
                }

                is Feature -> {
                    val active = key == selected
                    val sel = anim(key to "sel", 200).also { it.update(if (active) 1f else 0f) }.value
                    val hov = anim(key to "side", 140).also { it.update(if (hovered && ! active) 1f else 0f) }.value
                    if (sel > 0.01f) ctx.drawHorizontalGradient(x + 6f, y, SIDEBAR_W - 6f, ROW_H, Style.accentColor.withAlpha((60 * sel).toInt()), Style.accentColor.withAlpha(0))
                    if (hov > 0.01f) ctx.drawRect(x + 6f, y, SIDEBAR_W - 6f, ROW_H, Color(255, 255, 255, (10 * hov).toInt()))
                    val dot = anim(key to "dot", 200).also { it.update(if (key.enabled) 1f else 0f) }.value
                    if (dot > 0.01f) Style.roundRect(ctx, x + 13f, y + 4f, 7f, 7f, 3.5f, onDot.withAlpha((60 * dot).toInt()))
                    Style.roundRect(ctx, x + 14f, y + 5f, 5f, 5f, 2.5f, Style.mix(offDot, onDot, dot))
                    ctx.drawString(fit(label, SIDEBAR_W - 32f), x + 23f, y + 4f, if (active) accentLight() else if (key.enabled) textSoft else textFaint, shadow = active)
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
            add(Entry.Header(titleOf(category)))
            val byGroup = featuresIn(category).groupBy { it.group }
            for ((group, list) in byGroup) {
                if (byGroup.size > 1 && group.isNotBlank()) add(Entry.Header(group))
                list.forEach { add(Entry.FeatureCard(it)) }
            }
            return@buildList
        }

        add(Entry.FeatureCard(feature))
        // many sections: show them as tabs, one at a time
        val groups = sectionsOf(feature)
        if (groups.size >= 3) {
            val names = groups.map { it.first }
            val cur = tabOf[feature]?.takeIf { it in names } ?: names.first()
            add(Entry.Tabs(feature, names, cur))
            for (w in groups.first { it.first == cur }.second) if (w.config.visibility()) add(Entry.Option(feature, w, null))
            return@buildList
        }
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
        val lines = wrap(feature.description.orEmpty().replace("\\n", " "), (w - CARD_PAD * 2 - 60f) / DESC_SCALE).size.coerceAtLeast(0)
        // Room for the switch on top and the "N settings" link at the bottom.
        return maxOf(18f + lines * LINE_H + CARD_PAD, if (hasSettings(feature) && selected != feature) 32f else 22f)
    }

    private fun optionHeight(entry: Entry.Option, w: Float): Float {
        val lines = wrap(entry.widget.config.description.orEmpty(), (w - CARD_PAD * 2) / DESC_SCALE).size
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
        // tabs that don't fit on one line become a column on the left, settings beside it
        val tabsEntry = entries.firstNotNullOfOrNull { it as? Entry.Tabs }
        val rail = tabsEntry != null && tabLayout(tabsEntry.tabs, w).any { it[1] > 0f }
        val ox = if (rail) x + RAIL_W + 8f else x
        val ow = if (rail) w - RAIL_W - 8f else w
        val heights = entries.map {
            when (it) {
                is Entry.Header -> 16f
                is Entry.FeatureCard -> featureCardHeight(it.feature, w)
                is Entry.Option -> optionHeight(it, ow)
                is Entry.Tabs -> if (rail) 0f else (tabLayout(it.tabs, w).maxOf { r -> r[1] } + 22f)
            }
        }
        drawnTabs.clear()
        var total = heights.sum() + entries.size * CARD_GAP
        if (rail) {
            val idx = entries.indexOf(tabsEntry)
            val before = heights.take(idx).sum() + idx * CARD_GAP
            total = maxOf(total, before + tabsEntry!!.tabs.size * (RAIL_ROW + 3f))
        }
        scroll.update(total, h)

        drawnFeatureCards.clear()
        drawnOptionCards.clear()
        val shown = ArrayList<Widget<*>>()
        val insideContent = mx in x .. x + w && my in top .. top + h

        ctx.enableScissor(x.toInt(), top.toInt(), (x + w).toInt(), (top + h).toInt())
        val slide = (1f - contentAnim.value) * 12f
        var y = top - scroll.value + slide
        entries.forEachIndexed { i, entry ->
            val eh = heights[i]
            val visible = y + eh >= top && y <= top + h
            when (entry) {
                is Entry.Header -> if (visible) {
                    val cat = CategoryType.entries.firstOrNull { titleOf(it) == entry.text && selected == null && query.isBlank() }
                    var tx = x + 2f
                    if (cat != null) {
                        drawIcon(ctx, cat, tx, y + 3.5f, 1f)
                        tx += 13f
                    }
                    val big = cat != null
                    val hs = if (big) 1.15f else 1f
                    ctx.drawString("§l${entry.text}", tx, y + (if (big) 3f else 4f), accentLight(), scale = hs, shadow = false)
                    val on = headerOnCount(entry.text)
                    if (on != null) ctx.drawString(on, tx + 6f + uiWidth("§l${entry.text}") * hs, y + 5f, textFaint, shadow = false)
                    ctx.drawHorizontalGradient(x + 2f, y + 15f, (w - 4f) * 0.35f, 1f, Style.accentColor.withAlpha(140), line)
                    ctx.drawRect(x + 2f + (w - 4f) * 0.35f, y + 15f, (w - 4f) * 0.65f, 1f, line)
                }

                is Entry.FeatureCard -> {
                    val f = entry.feature
                    val rect = floatArrayOf(x, y, w, eh)
                    drawnFeatureCards += f to rect
                    if (visible) {
                        val hovered = insideContent && inside(mx, my, rect)
                        val on = anim(f to "on", 220).also { it.update(if (f.enabled) 1f else 0f) }.value
                        val hov = anim(f to "card", 150).also { it.update(if (hovered && selected != f) 1f else 0f) }.value
                        pressPush(ctx, f, rect, 0.012f)
                        Style.roundRect(ctx, x, y, w, eh, 4f, Style.mix(Style.mix(cardOff, card, on), cardHover, hov))
                        Style.drawGlow(ctx, x + 2f, y + 1f, 26f, eh - 2f, Style.accentColor, on)
                        ctx.drawRect(x, y + 1f, 2f, eh - 2f, Style.mix(line, Style.accentColor, on))
                        highlight(ctx, f.name, x + CARD_PAD + 3f, y + 6f, "§l")
                        ctx.drawString("§l${f.name}", x + CARD_PAD + 3f, y + 6f, if (f.enabled) textMain else textSoft)
                        var ly = y + 18f
                        for (l in wrap(f.description.orEmpty().replace("\\n", " "), (w - CARD_PAD * 2 - 60f) / DESC_SCALE)) {
                            highlight(ctx, l, x + CARD_PAD + 3f, ly + 0.5f, "", DESC_SCALE)
                            ctx.drawString(l, x + CARD_PAD + 3f, ly + 0.5f, if (f.enabled) textDim else textFaint, scale = DESC_SCALE, shadow = false)
                            ly += LINE_H
                        }
                        Style.drawSwitch(ctx, x + w - 32f, y + 6f, 24f, 12f, on)
                        if (selected != f && hasSettings(f)) {
                            val n = f.configSettings.size
                            val label = if (f is IFeatureScreen) "open ›" else "$n setting${if (n == 1) "" else "s"} ›"
                            ctx.drawString(label, x + w - 8f - uiWidth(label), y + eh - 12f, if (f.enabled) accentLight() else textFaint, shadow = false)
                        }
                        ctx.pose().popMatrix()
                    }
                }

                is Entry.Tabs -> if (rail) {
                    entry.tabs.forEachIndexed { ti, name ->
                        val rect = floatArrayOf(x, y + ti * (RAIL_ROW + 3f), RAIL_W, RAIL_ROW)
                        drawnTabs += name to rect
                        val sel = name == entry.current
                        val hov = anim("tab:${entry.feature.name}:$name", 120).also { it.update(if (insideContent && inside(mx, my, rect)) 1f else 0f) }.value
                        if (sel) {
                            Style.roundRect(ctx, rect[0], rect[1], rect[2], rect[3], 5f, Style.mix(card, cardHover, 0.9f))
                            ctx.drawVerticalGradient(rect[0] + 1f, rect[1] + 3f, 2f, rect[3] - 6f, Style.accentColor.withAlpha(240), Style.accent2.withAlpha(240))
                        }
                        else Style.roundRect(ctx, rect[0], rect[1], rect[2], rect[3], 5f, Style.mix(card, cardHover, hov).withAlpha((120 + 135 * hov).toInt()))
                        ctx.drawString(name, rect[0] + 9f, rect[1] + (RAIL_ROW - 8f) / 2f, if (sel) textMain else Style.mix(textDim, textSoft, hov), shadow = false)
                    }
                }
                else if (visible) {
                    val layout = tabLayout(entry.tabs, w)
                    entry.tabs.forEachIndexed { ti, name ->
                        val r = layout[ti]
                        val rect = floatArrayOf(x + r[0], y + 2f + r[1], r[2], r[3])
                        drawnTabs += name to rect
                        val sel = name == entry.current
                        val hov = anim("tab:${entry.feature.name}:$name", 120).also { it.update(if (insideContent && inside(mx, my, rect)) 1f else 0f) }.value
                        if (sel) Style.gradientPill(ctx, rect[0], rect[1], rect[2], rect[3], Style.accentColor.withAlpha(220), Style.accent2.withAlpha(220))
                        else Style.roundRect(ctx, rect[0], rect[1], rect[2], rect[3], 8f, Style.mix(card, cardHover, hov))
                        ctx.drawString(name, rect[0] + 9f, rect[1] + 4f, if (sel) textMain else Style.mix(textDim, textSoft, hov), shadow = false)
                    }
                }

                is Entry.Option -> {
                    val widget = entry.widget
                    if (visible) {
                        val ohov = anim(widget, 150).also { it.update(if (insideContent && my in y .. y + eh && mx in ox .. ox + ow) 1f else 0f) }.value
                        Style.roundRect(ctx, ox, y, ow, eh, 4f, Style.mix(card, cardHover, ohov * 0.6f))
                        drawnOptionCards += floatArrayOf(ox, y, ow, eh)
                        var wy = y + CARD_PAD
                        if (entry.prefix != null) {
                            ctx.drawString("§8${entry.prefix} ›", ox + CARD_PAD, wy + 1f, textFaint, shadow = false)
                            wy += LINE_H
                        }
                        widget.x = (ox + CARD_PAD).toInt()
                        widget.y = wy.toInt()
                        widget.width = (ow - CARD_PAD * 2).toInt()
                        widget.draw(ctx, mouseX, mouseY)
                        shown += widget
                        var ly = wy + widget.height + 3f
                        for (line in wrap(widget.config.description.orEmpty(), (ow - CARD_PAD * 2) / DESC_SCALE)) {
                            ctx.drawString(line, ox + CARD_PAD + 2f, ly + 0.5f, textDim, scale = DESC_SCALE, shadow = false)
                            ly += LINE_H
                        }
                    }
                    else {
                        widget.y = -10000
                    }
                }
            }
            if (entry is Entry.Header && entry.text == jumpTo) {
                scroll.scrollTo(y - (top - scroll.value + slide))
                jumpTo = null
            }
            y += eh + CARD_GAP
        }
        if (contentAnim.value < 0.999f) ctx.drawRect(x, top, w, h, Color(15, 12, 13, (230 * (1f - contentAnim.value)).toInt()))
        ctx.disableScissor()
        visibleWidgets = shown
        scroll.drawScrollbar(ctx, x + w + 2f, top, 2f, h, mx, my)
    }

    /** Soft wine -> rose marker behind the part of [text] that matches the search. */
    private fun highlight(ctx: GuiGraphicsExtractor, text: String, x: Float, y: Float, fmt: String, scale: Float = 1f) {
        val q = query.trim()
        if (q.isEmpty()) return
        var from = 0
        while (true) {
            val i = text.indexOf(q, from, ignoreCase = true)
            if (i < 0) return
            val hx = x + uiWidth(fmt + text.substring(0, i)) * scale
            val hw = uiWidth(fmt + text.substring(i, i + q.length)) * scale
            Style.gradientPill(ctx, hx - 1.5f, y - 1.5f, hw + 3f, 11f * scale, Style.accentColor.withAlpha(110), Style.accent2.withAlpha(110))
            from = i + q.length
        }
    }

    /** The accent, lifted towards white for text on dark backgrounds. */
    private fun accentLight(): Color {
        val a = Style.accentColor
        return Color((a.red + 255) / 2, (a.green + 255) / 2, (a.blue + 255) / 2)
    }

    /** "3 on" next to a category header (only for the category list view). */
    private fun headerOnCount(text: String): String? {
        if (selected != null || query.isNotBlank()) return null
        val inTab = featuresIn(category)
        val list = if (text in GROUP_ORDER && text.isNotBlank()) inTab.filter { it.group == text } else inTab
        val n = list.count { it.enabled }
        return if (n > 0) "$n on" else null
    }

    private fun hasSettings(f: Feature) = f is IFeatureScreen || f.configSettings.isNotEmpty()

    // ------------------------------------------------------------------ input

    override fun mouseClicked(mouseButtonEvent: MouseButtonEvent, bl: Boolean): Boolean {
        if (introT() < 0.7f) { introSkipped = true; return true }
        val mx = Resolution.getMouseX(mouseButtonEvent.x).toFloat()
        val my = Resolution.getMouseY(mouseButtonEvent.y).toFloat()
        val button = mouseButtonEvent.button()
        val left = button == InputConstants.MOUSE_BUTTON_LEFT

        if (search.mouseClicked(mx, my, mouseButtonEvent)) return true
        search.listening = false

        if (left) titleButtons().forEach { (label, rect) ->
            if (!inside(mx, my, rect)) return@forEach
            ripple(rect, mx, my, "btn:$label")
            Style.playClickSound(1f)
            when (label) {
                "✕" -> onClose()
                "HUD" -> { saveAndClear(); GuiUtils.setScreen(HudEditorScreen()) }
                "Classic" -> { saveAndClear(); GuiUtils.setScreen(ClickGuiScreen()) }
                "All Off", "All On" -> dev.marceline.features.MasterSwitch.toggle()
            }
            return true
        }

        if (left) drawnTabs.firstOrNull { inside(mx, my, it.second) }?.let { (name, rect) ->
            val f = selected ?: return@let
            ripple(rect, mx, my, "tab:$name")
            Style.playClickSound(1.1f)
            tabOf[f] = name
            scroll.reset()
            return true
        }
        if (left && sideScroll.mouseClicked(mx, my)) return true
        if (left && scroll.mouseClicked(mx, my)) return true

        val sideTop = winY() + TITLE_H + 6f
        val sideBottom = winY() + winH() - 6f
        if (my in sideTop .. sideBottom) drawnSideRows.firstOrNull { inside(mx, my, it.second) }?.let { (key, rect) ->
            if (!left) return true
            ripple(rect, mx, my)
            Style.playClickSound(1f)
            when (key) {
                is CategoryType -> { category = key; selected = null; query = "" }
                is Feature -> { category = key.category; selected = key; query = "" }
                is GroupRow -> { selected = null; query = ""; jumpTo = key.name; remember(); return true }
            }
            scroll.reset()
            remember()
            return true
        }

        val x = contentX()
        val top = contentY()
        if (mx !in x .. x + contentW() || my !in top .. top + contentH()) return super.mouseClicked(mouseButtonEvent, bl)

        drawnOptionCards.firstOrNull { inside(mx, my, it) }?.let { ripple(it, mx, my) }
        visibleWidgets.forEach { if (it.mouseClicked(mx.toDouble(), my.toDouble(), button)) return true }

        drawnFeatureCards.firstOrNull { inside(mx, my, it.second) }?.let { (f, rect) ->
            if (left) ripple(rect, mx, my, f)
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
            else NotificationManager.push("Marceline", "&fEnable &b${f.name} &ffirst to open its settings!")
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
        if (introT() < 0.7f && keyEvent.key != InputConstants.KEY_ESCAPE) { introSkipped = true; return true }
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
        contentAnim.set(0f)
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
        if (closing) return
        saveAndClear()
        closing = true // the frame loop finishes closing once the fade-out is done
    }

    /** One animation per thing on screen (card, row, switch...), created on first use. */
    private fun anim(key: Any, ms: Long): Animation = anims.getOrPut(key) { Animation(ms, 0f) }

    /** Category glyph in the wine -> rose accent, [strength] 0..1 fades it towards the dim text colour. */
    private fun drawIcon(ctx: GuiGraphicsExtractor, cat: CategoryType, x: Float, y: Float, strength: Float) {
        val t = cat.ordinal / (CategoryType.entries.size - 1f)
        val c = Style.mix(textFaint, Style.mix(Style.accentColor, Style.accent2, t), strength)
        drawCategoryIcon(ctx, cat, x, y - 0.5f, 9f, c)
    }

    /**
     * The logo intro: the letters rise in one after another, a light sweeps across them,
     * then it fades while the window opens. A click or a key skips it.
     */
    private fun drawIntro(ctx: GuiGraphicsExtractor, t: Float) {
        val fadeOut = if (t > 0.7f) 1f - (t - 0.7f) / 0.3f else 1f
        val dim = (minOf(1f, t / 0.15f) * 130).toInt()
        ctx.drawRect(0, 0, Resolution.width, Resolution.height, Color(6, 2, 14, dim))
        if (fadeOut <= 0.01f) return
        val word = "Marceline"
        val scale = 3.2f
        val widths = word.map { uiWidth("§l$it") * scale }
        var x = Resolution.width / 2f - widths.sum() / 2f
        val y = Resolution.height / 2f - 20f
        // a soft glow behind the word
        val glowA = (fadeOut * minOf(1f, t / 0.3f))
        // many faint layers, each a little bigger, so the glow fades out instead of ending in an edge
        val gw = widths.sum()
        val gh = 9f * scale
        for (i in 10 downTo 1) {
            val d = i * 4f
            val c = Style.mix(Style.accent2, Style.accentColor, i / 10f)
            Style.roundRect(ctx, x - d, y - d * 0.55f, gw + d * 2, gh + d * 1.1f, gh / 2f + d * 0.55f, c.withAlpha((7 * glowA).toInt()))
        }
        val sweep = (t - 0.28f) / 0.42f // 0..1 across the word
        word.forEachIndexed { i, ch ->
            val p = i / (word.length - 1f)
            val appear = ((t * 1.8f - i * 0.07f) / 0.25f).coerceIn(0f, 1f)
            val ease = 1f - (1f - appear) * (1f - appear)
            val shine = (1f - kotlin.math.abs(p - sweep) * 4f).coerceIn(0f, 1f) * 0.85f
            val base = Style.mix(logoFrom, logoTo, p)
            val c = Style.mix(base, Color.WHITE, shine)
            val alpha = (255 * ease * fadeOut).toInt().coerceIn(0, 255)
            if (alpha > 3) ctx.drawString("§l$ch", x, y + (1f - ease) * 8f, c.withAlpha(alpha), scale = scale, shadow = false)
            x += widths[i]
        }
        val sub = "v" + dev.marceline.Marceline.MOD_VERSION
        val subA = (((t - 0.35f) / 0.2f).coerceIn(0f, 1f) * fadeOut * 200).toInt()
        if (subA > 3) ctx.drawCenteredString(sub, Resolution.width / 2f, y + 9f * scale + 8f, textDim.withAlpha(subA), shadow = false)
    }

    /** "Marceline" in the smooth bold font, wine -> rose letter by letter. Returns where it ends. */
    private fun drawLogo(ctx: GuiGraphicsExtractor, x: Float, y: Float): Float {
        val word = "Marceline"
        if (! UiFont.active) {
            ctx.drawString(word, x, y, Color.WHITE)
            return x + mc.font.width(word)
        }
        var cx = x
        UiFont.displayed {
            word.forEachIndexed { i, ch ->
                val t = i / (word.length - 1f)
                ctx.drawString("§l$ch", cx, y - 0.5f, Style.mix(logoFrom, logoTo, t))
                cx += UiFont.width("§l$ch") + 0.3f
            }
        }
        return cx
    }

}
