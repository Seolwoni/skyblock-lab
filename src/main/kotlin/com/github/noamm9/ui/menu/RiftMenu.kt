package com.github.noamm9.ui.menu

import com.github.noamm9.NoammAddons
import com.github.noamm9.NoammAddons.mc
import com.github.noamm9.ui.clickgui.SkyHanniTheme
import com.github.noamm9.ui.clickgui.components.settings.Style
import com.github.noamm9.utils.ColorUtils.withAlpha
import com.github.noamm9.utils.render.Render2D.drawRect
import com.github.noamm9.utils.render.Render2D.drawString
import com.github.noamm9.utils.render.Render2D.drawVerticalGradient
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import java.awt.Color

/**
 * Shared look of the Riftwalk title screen and ESC menu: blurred background with a violet tint,
 * the Riftwalk mark and word in the middle, flat see-through buttons with spaced capitals.
 * The screens extend the game's TitleScreen / PauseScreen, so anything that checks for those still works.
 */
class RiftMenu(private val screen: Screen, private val markSize: Float, private val subtitle: String, private val rows: () -> List<List<MenuButton>>) {
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

    /** The violet tint over the (already blurred) background. */
    fun tint(graphics: GuiGraphicsExtractor) =
        graphics.drawVerticalGradient(0, 0, width, height, Color(12, 6, 28, 120), Color(28, 8, 40, 175))

    fun render(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, corners: (GuiGraphicsExtractor, Float) -> Unit) {
        // the menu's smooth font (Pretendard), like the settings window
        val prev = com.github.noamm9.utils.render.UiFont.active
        com.github.noamm9.utils.render.UiFont.active = com.github.noamm9.features.impl.dev.ClickGui.customFont.value
        try { draw(graphics, mouseX, mouseY, corners) } finally { com.github.noamm9.utils.render.UiFont.active = prev }
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

        // RIFTWALK, spaced and blended violet -> pink
        val word = "RIFTWALK"
        val scale = 1.8f
        val spacing = 2.6f
        val widths = word.map { SkyHanniTheme.uiWidth("§l$it") * scale }
        val total = widths.sum() + spacing * (word.length - 1)
        var x = cx - total / 2f
        val wy = top + markSize + 8f + rise
        word.forEachIndexed { i, ch ->
            val c = Style.mix(Color.WHITE, Style.mix(SkyHanniTheme.logoFrom, SkyHanniTheme.logoTo, i / (word.length - 1f)), 0.35f)
            graphics.drawString("§l$ch", x, wy, c.withAlpha((255 * f).toInt()), scale = scale, shadow = false)
            x += widths[i] + spacing
        }
        spaced(graphics, subtitle, cx, wy + 9f * scale + 4f, 0.75f, 1.6f, Color(205, 195, 230, (170 * f).toInt()))

        for (b in buttons) drawButton(graphics, b, mouseX.toDouble(), mouseY.toDouble(), f)
        graphics.drawString("v${NoammAddons.MOD_VERSION} · Minecraft 26.2", 4f, height - 11f, Color(200, 190, 225, (150 * f).toInt()), scale = 0.85f, shadow = false)
        corners(graphics, f)
    }

    private fun drawButton(g: GuiGraphicsExtractor, b: MenuButton, mx: Double, my: Double, f: Float) {
        val target = if (b.enabled && b.inside(mx, my)) 1f else 0f
        b.hover += (target - b.hover) * 0.3f
        val h = b.hover
        val alpha = f * (if (b.enabled) 1f else 0.45f)
        val tint = if (b.danger) Color(251, 113, 133) else SkyHanniTheme.logoFrom
        Style.roundRect(g, b.x, b.y, b.w, b.h, 3f, Style.mix(Color(255, 255, 255), tint, h * 0.5f).withAlpha(((22 + 30 * h) * alpha).toInt()))
        val edge = Style.mix(Color(255, 255, 255), tint, h).withAlpha(((45 + 90 * h) * alpha).toInt())
        g.drawRect(b.x + 3f, b.y, b.w - 6f, 1f, edge)
        g.drawRect(b.x + 3f, b.y + b.h - 1f, b.w - 6f, 1f, edge)
        g.drawRect(b.x, b.y + 3f, 1f, b.h - 6f, edge)
        g.drawRect(b.x + b.w - 1f, b.y + 3f, 1f, b.h - 6f, edge)
        if (h > 0.02f) {
            val lw = (b.w - 24f) * h
            g.drawRect(b.x + (b.w - lw) / 2f, b.y + b.h - 2f, lw, 1f, tint.withAlpha((220 * h * alpha).toInt()))
        }
        spaced(g, b.label, b.x + b.w / 2f, b.y + (b.h - 8f * 0.85f) / 2f, 0.85f, 1.4f, Color.WHITE.withAlpha(((200 + 55 * h) * alpha).toInt()))
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
        fun riftwalkButton() = MenuButton("RIFTWALK") { open(com.github.noamm9.features.impl.dev.ClickGui.createScreen()) }

        const val MENU_W = 200f
        const val BUTTON_H = 20f
        const val GAP = 4f
    }
}

class RiftTitleScreen: net.minecraft.client.gui.screens.TitleScreen(false) {
    private val menu = RiftMenu(this, 46f, "SKYBLOCK TOOLKIT") {
        buildList {
            add(listOf(RiftMenu.MenuButton("SINGLEPLAYER") { RiftMenu.open(net.minecraft.client.gui.screens.worldselection.SelectWorldScreen(this@RiftTitleScreen)) }))
            add(listOf(RiftMenu.MenuButton("MULTIPLAYER") { RiftMenu.open(net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen(this@RiftTitleScreen)) }))
            add(listOf(
                RiftMenu.MenuButton("OPTIONS") { RiftMenu.open(net.minecraft.client.gui.screens.options.OptionsScreen(this@RiftTitleScreen, mc.options, false)) },
                RiftMenu.riftwalkButton(),
            ))
            val quit = RiftMenu.MenuButton("QUIT GAME", danger = true) { mc.stop() }
            add(if (RiftMenu.hasModMenu) listOf(RiftMenu.modsButton(this@RiftTitleScreen), quit) else listOf(quit))
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
            g.drawString(c, width - w - 4f, height - 11f, Color(200, 190, 225, (150 * f).toInt()), scale = 0.85f, shadow = false)
        }

    override fun mouseClicked(event: MouseButtonEvent, isDoubleClick: Boolean) = menu.click(event)
    override fun shouldCloseOnEsc() = false
}

class RiftPauseScreen: net.minecraft.client.gui.screens.PauseScreen(true) {
    private val menu = RiftMenu(this, 34f, "GAME MENU") {
        buildList {
            val player = mc.player
            add(listOf(RiftMenu.MenuButton("BACK TO GAME") { RiftMenu.open(null) }))
            add(listOf(
                RiftMenu.MenuButton("ADVANCEMENTS") { player?.let { RiftMenu.open(net.minecraft.client.gui.screens.advancements.AdvancementsScreen(it.connection.advancements, this@RiftPauseScreen)) } },
                RiftMenu.MenuButton("STATISTICS") { player?.let { RiftMenu.open(net.minecraft.client.gui.screens.achievement.StatsScreen(this@RiftPauseScreen, it.stats)) } },
            ))
            add(listOf(
                RiftMenu.MenuButton("OPTIONS") { RiftMenu.open(net.minecraft.client.gui.screens.options.OptionsScreen(this@RiftPauseScreen, mc.options, true)) },
                RiftMenu.riftwalkButton(),
            ))
            if (RiftMenu.hasModMenu) add(listOf(RiftMenu.modsButton(this@RiftPauseScreen)))
            lateinit var leave: RiftMenu.MenuButton
            leave = RiftMenu.MenuButton(if (mc.isLocalServer) "SAVE AND QUIT TO TITLE" else "DISCONNECT", danger = true) {
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
