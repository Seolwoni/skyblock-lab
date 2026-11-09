package dev.marceline.ui.menu

import dev.marceline.Marceline
import dev.marceline.Marceline.mc
import dev.marceline.ui.clickgui.SkyHanniTheme
import dev.marceline.ui.clickgui.components.settings.Style
import dev.marceline.utils.ColorUtils.withAlpha
import dev.marceline.utils.render.Render2D.drawRect
import dev.marceline.utils.render.Render2D.drawString
import dev.marceline.utils.render.Render2D.drawVerticalGradient
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import java.awt.Color

/**
 * Shared look of the Marceline title screen and ESC menu: blurred background with a wine tint,
 * the Marceline mark and word in the middle, flat see-through buttons with spaced capitals.
 * The screens extend the game's TitleScreen / PauseScreen, so anything that checks for those still works.
 */
class StyledMenu(private val screen: Screen, private val markSize: Float, private val subtitle: String, private val rows: () -> List<List<MenuButton>>) {
    class MenuButton(val label: String, val danger: Boolean = false, val action: () -> Unit) {
        var x = 0f; var y = 0f; var w = 0f; var h = BUTTON_H
        var hover = 0f
        var enabled = true
        fun inside(mx: Double, my: Double) = mx >= x && mx <= x + w && my >= y && my <= y + h
    }

    private val buttons = ArrayList<MenuButton>()
    private val openedAt = System.currentTimeMillis()
    private val width get() = screen.width
    private val height get() = screen.height

    /** Lays the buttons out, row by row: one button fills the row, two share it. */
    fun init() {
        buttons.clear()
        val rows = rows()
        val headH = markSize + 8f + 14f + 10f + 18f
        val bodyH = rows.size * BUTTON_H + (rows.size - 1) * GAP
        var y = ((height - headH - bodyH) / 2f).coerceAtLeast(8f) + headH
        val left = width / 2f - MENU_W / 2f
        for (row in rows) {
            val w = (MENU_W - GAP * (row.size - 1)) / row.size
            row.forEachIndexed { i, b ->
                b.x = left + i * (w + GAP); b.y = y; b.w = w
                buttons += b
            }
            y += BUTTON_H + GAP
        }
    }

    private fun fade() = ((System.currentTimeMillis() - openedAt) / 350f).coerceIn(0f, 1f).let { 1f - (1f - it) * (1f - it) }

    /** The wine tint over the (already blurred) background. */
    fun tint(graphics: GuiGraphicsExtractor) {
        graphics.drawVerticalGradient(0, 0, width, height, Color(14, 8, 10, 130), Color(36, 8, 16, 185))
        // soft vignette: darker edges keep the eye in the middle
        val band = (minOf(width, height) * 0.18f).toInt().coerceAtLeast(1)
        graphics.drawVerticalGradient(0, 0, width, band, Color(0, 0, 0, 90), Color(0, 0, 0, 0))
        graphics.drawVerticalGradient(0, height - band, width, band, Color(0, 0, 0, 0), Color(0, 0, 0, 110))
    }

    fun render(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, corners: (GuiGraphicsExtractor, Float) -> Unit) {
        // the menu's smooth font (Pretendard), like the settings window
        val prev = dev.marceline.utils.render.UiFont.active
        dev.marceline.utils.render.UiFont.active = dev.marceline.features.impl.dev.ClickGui.customFont.value
        try { draw(graphics, mouseX, mouseY, corners) } finally { dev.marceline.utils.render.UiFont.active = prev; dev.marceline.utils.render.UiFont.display = false }
    }

    private fun draw(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, corners: (GuiGraphicsExtractor, Float) -> Unit) {
        val f = fade()
        val cx = width / 2f
        val top = (buttons.firstOrNull()?.y ?: (height / 2f)) - (markSize + 8f + 14f + 10f + 18f)
        val rise = (1f - f) * 6f

        // mark with a soft glow behind it
        for (i in 6 downTo 1) {
            val d = i * 3f
            Style.roundRect(graphics, cx - markSize / 2f - d, top - d + rise, markSize + d * 2, markSize + d * 2, markSize / 2f + d, SkyHanniTheme.logoFrom.withAlpha((6 * f).toInt()))
        }
        SkyHanniTheme.drawLogoMark(graphics, cx - markSize / 2f, top + rise, markSize, Color.WHITE.withAlpha((255 * f).toInt()), big = true)

        dev.marceline.utils.render.UiFont.display = true
        // MARCELINE, spaced and blended wine -> rose
        val word = "MARCELINE"
        val scale = 2.0f
        val spacing = 2.8f
        val widths = word.map { SkyHanniTheme.uiWidth("§l$it") * scale }
        val total = widths.sum() + spacing * (word.length - 1)
        var x = cx - total / 2f
        val wy = top + markSize + 8f + rise
        val ivory = Color(243, 234, 222)
        word.forEachIndexed { i, ch ->
            val c = Style.mix(ivory, Style.mix(SkyHanniTheme.logoFrom, SkyHanniTheme.logoTo, i / (word.length - 1f)), 0.22f)
            graphics.drawString("§l$ch", x, wy, c.withAlpha((255 * f).toInt()), scale = scale, shadow = false)
            x += widths[i] + spacing
        }
        // gold hairline that fades out at both ends, then the subtitle in gold
        val ly = wy + 9f * scale + 3f
        val half = total / 2f + 10f
        val steps = 24
        for (k in 0 until steps) {
            val t0 = k / steps.toFloat()
            val a = (1f - kotlin.math.abs(t0 * 2f - 1f)).let { it * it }
            graphics.drawRect(cx - half + 2f * half * t0, ly, 2f * half / steps + 0.5f, 0.6f, SkyHanniTheme.gold.withAlpha((200 * a * f).toInt()))
        }
        spaced(graphics, subtitle, cx, ly + 4f, 0.72f, 2.2f, SkyHanniTheme.gold.withAlpha((210 * f).toInt()))

        for (b in buttons) drawButton(graphics, b, mouseX.toDouble(), mouseY.toDouble(), f)
        dev.marceline.utils.render.UiFont.display = false
        graphics.drawString("v${Marceline.MOD_VERSION} · Minecraft 26.2", 4f, height - 11f, Color(225, 200, 202, (150 * f).toInt()), scale = 0.85f, shadow = false)
        corners(graphics, f)
    }

    private fun drawButton(g: GuiGraphicsExtractor, b: MenuButton, mx: Double, my: Double, f: Float) {
        val target = if (b.enabled && b.inside(mx, my)) 1f else 0f
        b.hover += (target - b.hover) * 0.3f
        val h = b.hover
        val alpha = f * (if (b.enabled) 1f else 0.45f)
        val tint = if (b.danger) Color(232, 80, 106) else SkyHanniTheme.logoFrom
        val r = 6f
        // a light rounded rim, then dark glass inside it: the rim shows only at the edge, corners stay round
        val rim = Style.mix(Color(255, 236, 220), tint, h).withAlpha(((48 + 92 * h) * alpha).toInt())
        Style.roundRect(g, b.x, b.y, b.w, b.h, r, rim)
        Style.roundRect(g, b.x + 1f, b.y + 1f, b.w - 2f, b.h - 2f, r - 1f, Color(14, 10, 12, ((150 - 25 * h) * alpha).toInt()))
        if (h > 0.02f) Style.roundRect(g, b.x + 1f, b.y + 1f, b.w - 2f, b.h - 2f, r - 1f, tint.withAlpha((34 * h * alpha).toInt()))
        if (h > 0.02f) {
            val lw = (b.w - 2 * r - 12f) * h
            g.drawRect(b.x + (b.w - lw) / 2f, b.y + b.h - 3f, lw, 0.75f, (if (b.danger) tint else SkyHanniTheme.gold).withAlpha((220 * h * alpha).toInt()))
        }
        spaced(g, b.label, b.x + b.w / 2f, b.y + (b.h - 8f * 0.95f) / 2f, 0.95f, 1.5f, Color.WHITE.withAlpha(((200 + 55 * h) * alpha).toInt()))
    }

    /** Capitals with extra room between letters, centred on [cx]. */
    fun spaced(g: GuiGraphicsExtractor, text: String, cx: Float, y: Float, scale: Float, spacing: Float, color: Color) {
        val widths = text.map { SkyHanniTheme.uiWidth(it.toString()) * scale }
        val total = widths.sum() + spacing * (text.length - 1).coerceAtLeast(0)
        var x = cx - total / 2f
        text.forEachIndexed { i, ch ->
            if (ch != ' ') g.drawString(ch.toString(), x, y, color, scale = scale, shadow = false)
            x += widths[i] + spacing
        }
    }

    /** True when a button took the click. */
    fun click(event: MouseButtonEvent): Boolean {
        if (event.button() != 0) return false
        val b = buttons.firstOrNull { it.enabled && it.inside(event.x, event.y) } ?: return false
        Style.playClickSound(1f)
        b.action()
        return true
    }

    companion object {
        fun open(screen: Screen?) = mc.gui.setScreen(screen)
        val hasModMenu get() = FabricLoader.getInstance().isModLoaded("modmenu")
        fun modsButton(parent: Screen) = MenuButton("MODS") { open(com.terraformersmc.modmenu.gui.ModsScreen(parent)) }
        fun marcelineButton() = MenuButton("MARCELINE") { open(dev.marceline.features.impl.dev.ClickGui.createScreen()) }

        const val MENU_W = 200f
        const val BUTTON_H = 20f
        const val GAP = 4f
    }
}

class MarcelineTitleScreen: net.minecraft.client.gui.screens.TitleScreen(false) {
    private val menu = StyledMenu(this, 46f, "SLOW DANCE WITH YOU") {
        buildList {
            add(listOf(StyledMenu.MenuButton("SINGLEPLAYER") { StyledMenu.open(net.minecraft.client.gui.screens.worldselection.SelectWorldScreen(this@MarcelineTitleScreen)) }))
            add(listOf(StyledMenu.MenuButton("MULTIPLAYER") { StyledMenu.open(net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen(this@MarcelineTitleScreen)) }))
            add(listOf(
                StyledMenu.MenuButton("OPTIONS") { StyledMenu.open(net.minecraft.client.gui.screens.options.OptionsScreen(this@MarcelineTitleScreen, mc.options, false)) },
                StyledMenu.marcelineButton(),
            ))
            val quit = StyledMenu.MenuButton("QUIT GAME", danger = true) { mc.stop() }
            add(if (StyledMenu.hasModMenu) listOf(StyledMenu.modsButton(this@MarcelineTitleScreen), quit) else listOf(quit))
        }
    }

    override fun init() = menu.init()
    override fun tick() = Unit
    override fun added() = Unit
    override fun removed() = Unit

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        extractPanorama(graphics, a)
        extractBlurredBackground(graphics)
        menu.tint(graphics)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) =
        menu.render(graphics, mouseX, mouseY) { g, f ->
            val c = "Copyright Mojang AB. Do not distribute!"
            val w = SkyHanniTheme.uiWidth(c) * 0.85f
            g.drawString(c, width - w - 4f, height - 11f, Color(225, 200, 202, (150 * f).toInt()), scale = 0.85f, shadow = false)
        }

    override fun mouseClicked(event: MouseButtonEvent, isDoubleClick: Boolean) = menu.click(event)
    override fun shouldCloseOnEsc() = false
}

class MarcelinePauseScreen: net.minecraft.client.gui.screens.PauseScreen(true) {
    private val menu = StyledMenu(this, 34f, "GAME MENU") {
        buildList {
            val player = mc.player
            add(listOf(StyledMenu.MenuButton("BACK TO GAME") { StyledMenu.open(null) }))
            add(listOf(
                StyledMenu.MenuButton("ADVANCEMENTS") { player?.let { StyledMenu.open(net.minecraft.client.gui.screens.advancements.AdvancementsScreen(it.connection.advancements, this@MarcelinePauseScreen)) } },
                StyledMenu.MenuButton("STATISTICS") { player?.let { StyledMenu.open(net.minecraft.client.gui.screens.achievement.StatsScreen(this@MarcelinePauseScreen, it.stats)) } },
            ))
            add(listOf(
                StyledMenu.MenuButton("OPTIONS") { StyledMenu.open(net.minecraft.client.gui.screens.options.OptionsScreen(this@MarcelinePauseScreen, mc.options, true)) },
                StyledMenu.marcelineButton(),
            ))
            if (StyledMenu.hasModMenu) add(listOf(StyledMenu.modsButton(this@MarcelinePauseScreen)))
            lateinit var leave: StyledMenu.MenuButton
            leave = StyledMenu.MenuButton(if (mc.isLocalServer) "SAVE AND QUIT TO TITLE" else "DISCONNECT", danger = true) {
                leave.enabled = false
                mc.disconnectFromWorld(Component.translatable("multiplayer.status.quitting"))
            }
            add(listOf(leave))
        }
    }

    override fun init() = menu.init()
    override fun tick() = Unit
    override fun added() = Unit
    override fun removed() = Unit

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        extractBlurredBackground(graphics)
        menu.tint(graphics)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) =
        menu.render(graphics, mouseX, mouseY) { _, _ -> }

    override fun mouseClicked(event: MouseButtonEvent, isDoubleClick: Boolean) = menu.click(event)
}
