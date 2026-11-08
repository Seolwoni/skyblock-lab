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
import com.github.noamm9.ui.utils.Animation
import com.github.noamm9.ui.utils.MouseHelper
import com.github.noamm9.ui.utils.Resolution
import com.github.noamm9.ui.utils.ScrollState
import com.github.noamm9.ui.utils.TextInputHandler
import com.github.noamm9.utils.render.UiFont
import com.github.noamm9.utils.ColorUtils.withAlpha
import com.github.noamm9.utils.GuiUtils
import com.github.noamm9.utils.render.Render2D.drawBorder
import com.github.noamm9.utils.render.Render2D.drawCenteredString
import com.github.noamm9.utils.render.Render2D.drawHorizontalGradient
import com.github.noamm9.utils.render.Render2D.drawRect
import com.github.noamm9.utils.render.Render2D.drawVerticalGradient
import com.github.noamm9.utils.render.Render2D.drawString
import com.github.noamm9.utils.render.Render2D.drawTexture
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import java.awt.Color
import com.github.noamm9.NoammAddons.mc

/** The Riftwalk menu's sizes, Void Rift palette, category icons and text helpers (used by [SkyHanniScreen]). */
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

    // Void Rift palette
    /** 0 = solid, 1 = most see-through (Dev > ClickGui > Glass). */
    val glass get() = com.github.noamm9.features.impl.dev.ClickGui.glass.value / 100f
    fun glassA(solid: Int, drop: Int) = (solid - drop * glass).toInt().coerceIn(0, 255)
    val bg get() = Color(15, 11, 23, glassA(250, 120))
    val panel get() = Color(21, 15, 32, glassA(255, 150))
    val panelTop get() = Color(32, 22, 50, glassA(255, 130))
    val card get() = Color(26, 20, 38, glassA(255, 110))
    val cardOff get() = Color(20, 15, 30, glassA(255, 120))
    val cardHover get() = Color(37, 27, 55, glassA(255, 90))
    val field = Color(11, 8, 18, 255)
    val line = Color(42, 31, 61, 255)
    val textMain = Color(237, 231, 255)
    val textSoft = Color(196, 184, 224)
    val textDim = Color(138, 127, 163)
    val textFaint = Color(93, 84, 112)
    val onDot = Color(236, 72, 153)
    val offDot = Color(52, 41, 72)
    val logoFrom = Color(139, 92, 246)
    val logoTo = Color(236, 72, 153)

    /** Icon texture per category (white, tinted when drawn). */
    val icons = CategoryType.entries.associateWith {
        net.minecraft.resources.Identifier.fromNamespaceAndPath(com.github.noamm9.NoammAddons.MOD_ID, "textures/gui/icons/${it.name.lowercase()}.png")
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

    /** The Riftwalk mark (hexagon + rift), white so it can be tinted. 64 px and 256 px versions. */
    val logoSmall = net.minecraft.resources.Identifier.fromNamespaceAndPath(com.github.noamm9.NoammAddons.MOD_ID, "textures/gui/logo_small.png")
    val logoBig = net.minecraft.resources.Identifier.fromNamespaceAndPath(com.github.noamm9.NoammAddons.MOD_ID, "textures/gui/logo.png")

    /** Draws the mark [size] GUI pixels wide at x, y. */
    fun drawLogoMark(ctx: GuiGraphicsExtractor, x: Float, y: Float, size: Float, color: Color, big: Boolean = false) {
        val px = if (big) 256 else 64
        ctx.pose().pushMatrix()
        ctx.pose().translate(x, y)
        ctx.pose().scale(size / px, size / px)
        ctx.drawTexture(if (big) logoBig else logoSmall, 0, 0, px, px, color)
        ctx.pose().popMatrix()
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
