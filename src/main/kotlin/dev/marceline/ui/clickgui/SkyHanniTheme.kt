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
import dev.marceline.Marceline.mc

/** The Marceline menu's sizes, Marceline palette, category icons and text helpers (used by [SkyHanniScreen]). */
internal object SkyHanniTheme {
    const val MARGIN_X = 40f
    const val MARGIN_Y = 22f
    const val TITLE_H = 26f
    const val SIDEBAR_W = 150f
    const val ROW_H = 15f
    const val CARD_PAD = 5f
    const val CARD_GAP = 5f
    const val LINE_H = 10f
    /** Descriptions a step smaller than names, so names lead. */
    const val DESC_SCALE = 0.9f

    // Marceline palette
    /** 0 = solid, 1 = most see-through (Dev > ClickGui > Glass). */
    val glass get() = dev.marceline.features.impl.dev.ClickGui.glass.value / 100f
    fun glassA(solid: Int, drop: Int) = (solid - drop * glass).toInt().coerceIn(0, 255)
    val bg get() = Color(15, 12, 13, glassA(250, 120))
    val panel get() = Color(22, 17, 19, glassA(255, 150))
    val panelTop get() = Color(34, 25, 28, glassA(255, 130))
    val card get() = Color(29, 22, 25, glassA(255, 110))
    val cardOff get() = Color(21, 16, 18, glassA(255, 120))
    val cardHover get() = Color(42, 30, 34, glassA(255, 90))
    val field = Color(11, 9, 10, 255)
    val line = Color(56, 40, 44, 255)
    val textMain = Color(243, 234, 222)
    val textSoft = Color(214, 198, 186)
    val textDim = Color(160, 140, 132)
    val textFaint = Color(108, 92, 90)
    val onDot = Color(232, 80, 106)
    val offDot = Color(64, 46, 48)
    /** Antique gold for small details (hairlines, the chat arrow, subtitles). */
    val gold = Color(201, 164, 106)
    val logoFrom = Color(179, 38, 62)
    val logoTo = Color(232, 80, 106)

    /** Icon texture per category (white, tinted when drawn). */
    val icons = CategoryType.entries.associateWith {
        net.minecraft.resources.Identifier.fromNamespaceAndPath(dev.marceline.Marceline.MOD_ID, "textures/gui/icons/${it.name.lowercase()}.png")
    }

    /** Draws a category icon [size] GUI pixels wide at x, y, tinted with [color]. */
    fun drawCategoryIcon(ctx: GuiGraphicsExtractor, cat: CategoryType, x: Float, y: Float, size: Float, color: Color) {
        ctx.pose().pushMatrix()
        ctx.pose().translate(x, y)
        ctx.pose().scale(size / 64f, size / 64f)
        ctx.drawTexture(icons.getValue(cat), 0, 0, 64, 64, color)
        ctx.pose().popMatrix()
    }

    fun titleOf(cat: CategoryType) = cat.title

    /**
     * The Marceline emblem (red guitar and bat in a gold ring), in colour: draw it with white. It comes pre-shrunk to
     * every size from 12 to 256 pixels (textures/gui/emblem/<n>.png) and is drawn at the one matching the screen
     * pixels it covers, one texture pixel per screen pixel: no blocky edges from shrinking it while drawing.
     */
    private val emblemSizes = (12 .. 96 step 2) + (100 .. 256 step 4)
    private val emblems = HashMap<Int, net.minecraft.resources.Identifier>()
    private fun emblem(n: Int) = emblems.getOrPut(n) { net.minecraft.resources.Identifier.fromNamespaceAndPath(dev.marceline.Marceline.MOD_ID, "textures/gui/emblem/$n.png") }

    /** Draws the mark [size] GUI pixels wide at x, y ([big] kept for callers; the size decides). */
    fun drawLogoMark(ctx: GuiGraphicsExtractor, x: Float, y: Float, size: Float, color: Color, big: Boolean = false) {
        val pose = ctx.pose()
        // screen pixels per GUI unit here: the window's GUI scale times whatever scale the caller has pushed
        val local = kotlin.math.sqrt(pose.m00() * pose.m00() + pose.m01() * pose.m01())
        val perUnit = (mc.window.guiScale.toFloat() * local).coerceAtLeast(0.1f)
        val want = size * perUnit
        val n = emblemSizes.minBy { kotlin.math.abs(it - want) }
        // start on a whole screen pixel so the texture's pixels line up with the screen's
        val sx = kotlin.math.round(x * perUnit) / perUnit
        val sy = kotlin.math.round(y * perUnit) / perUnit
        pose.pushMatrix()
        pose.translate(sx, sy)
        pose.scale(1f / perUnit, 1f / perUnit)
        ctx.drawTexture(emblem(n), 0, 0, n, n, color)
        pose.popMatrix()
    }



    // ------------------------------------------------------------------ text helpers


    fun uiWidth(s: String) = if (UiFont.active) UiFont.width(s) else mc.font.width(s)

    fun inside(mx: Float, my: Float, r: FloatArray) = mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3]

    fun textWidth(s: String) = if (UiFont.active) UiFont.width(s) else mc.font.width(s.replace(Regex("[§&][0-9a-fk-or]"), ""))

    fun fit(s: String, max: Float): String {
        if (textWidth(s) <= max) return s
        var out = s
        while (out.isNotEmpty() && textWidth("$out…") > max) out = out.dropLast(1)
        return "$out…"
    }

    val wrapCache = HashMap<Pair<String, Int>, List<String>>()

    fun wrap(text: String, max: Float): List<String> {
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
