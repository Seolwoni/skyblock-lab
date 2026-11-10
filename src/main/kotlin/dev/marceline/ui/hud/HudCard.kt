package dev.marceline.ui.hud

import dev.marceline.Marceline.MOD_ID
import dev.marceline.features.impl.dev.ClickGui
import dev.marceline.ui.clickgui.components.settings.Style
import dev.marceline.utils.render.Render2D.drawVerticalGradient
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import java.awt.Color

/**
 * The one card every Marceline HUD sits on, the same as the scoreboard: a soft shadow, a warm hairline rim,
 * wine-black glass with light along the top and depth along the bottom, film grain, and a gold hairline on top.
 */
object HudCard {
    private val GRAIN = Identifier.fromNamespaceAndPath(MOD_ID, "textures/gui/grain.png")
    private const val GRAIN_TILE = 128

    val glass = Color(19, 11, 14)

    fun draw(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, radius: Float = 4.5f, alpha: Float = 1f, glassAlpha: Int = 176, rule: Boolean = true) {
        if (w <= 0f || h <= 0f || alpha <= 0.01f) return
        fun a(v: Int) = (v * alpha).toInt().coerceIn(0, 255)
        Style.drawShadow(ctx, x, y, w, h, radius, 0.55f * alpha)
        Style.roundRect(ctx, x - 0.5f, y - 0.5f, w + 1f, h + 1f, radius + 0.5f, Color(255, 236, 220, a(22)))
        Style.roundRect(ctx, x, y, w, h, radius, Color(glass.red, glass.green, glass.blue, a(glassAlpha)))
        inner(ctx, x, y, w, h, radius, alpha)
        if (rule) Style.goldHairline(ctx, x + radius + 2f, y + 0.3f, w - radius * 2 - 4f, 0.75f * alpha, 0.6f)
    }

    /** Light along the top, shade along the bottom, then grain: what turns a flat fill into a surface. */
    fun inner(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, radius: Float, alpha: Float = 1f) {
        fun a(v: Int) = (v * alpha).toInt().coerceIn(0, 255)
        val inset = radius * 0.6f
        val top = minOf(9f, h / 2f)
        val bottom = minOf(12f, h / 2f)
        ctx.drawVerticalGradient(x + inset, y + 0.5f, w - inset * 2, top, Color(255, 240, 225, a(13)), Color(255, 240, 225, 0))
        ctx.drawVerticalGradient(x + inset, y + h - bottom - 0.5f, w - inset * 2, bottom, Color(6, 1, 3, 0), Color(6, 1, 3, a(44)))
        grain(ctx, x + 1f, y + 1f, w - 2f, h - 2f, alpha)
    }

    /** Film grain over a rectangle, two texels per GUI pixel. Off in mobile mode or with the setting off. */
    fun grain(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, alpha: Float = 1f, strength: Float = 1f) {
        if (! ClickGui.grain.value || ClickGui.mobileMode.value || w <= 2f || h <= 2f) return
        val tint = Color(255, 255, 255, (46 * alpha * strength).toInt().coerceIn(0, 255)).rgb
        ctx.pose().pushMatrix()
        ctx.pose().translate(x, y)
        ctx.pose().scale(0.5f, 0.5f)
        val tw = (w * 2).toInt()
        val th = (h * 2).toInt()
        var ty = 0
        while (ty < th) {
            val ch = minOf(GRAIN_TILE, th - ty)
            var tx = 0
            while (tx < tw) {
                val cw = minOf(GRAIN_TILE, tw - tx)
                ctx.blit(RenderPipelines.GUI_TEXTURED, GRAIN, tx, ty, 0f, 0f, cw, ch, GRAIN_TILE, GRAIN_TILE, tint)
                tx += GRAIN_TILE
            }
            ty += GRAIN_TILE
        }
        ctx.pose().popMatrix()
    }
}
