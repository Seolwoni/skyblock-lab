package dev.marceline.ui.menu

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier

/**
 * The game's loading screen (start-up and resource reloads) in the Marceline look: dark wine background with a soft
 * rose glow, the emblem, the MARCELINE wordmark and a thin gold progress line. Drawn over the vanilla overlay
 * (MixinLoadingOverlay) with the same fade.
 *
 * Runs while the game's resources are still loading, so it touches nothing that needs them: its pictures are read
 * straight from the mod jar into textures of its own, it uses no fonts and no shaders of the mod, and no feature
 * or config object.
 */
object LoadingLook {
    /** Background the vanilla overlay clears to and fills with (also used for its own fill). */
    const val BACKGROUND = 0xFF140809.toInt()

    private val emblemSizes = (12 .. 96 step 2) + (100 .. 256 step 4)
    private val wordSizes = (8 .. 96 step 2).toList()
    private val loaded = HashMap<String, Identifier?>()

    /** A picture from the jar as a texture of our own; null if it could not be read (then that part is skipped). */
    private fun texture(path: String): Identifier? = loaded.getOrPut(path) {
        runCatching {
            val stream = LoadingLook::class.java.getResourceAsStream("/assets/marceline/$path") ?: return@runCatching null
            val image = stream.use { NativeImage.read(it) }
            val id = Identifier.fromNamespaceAndPath("marceline", "loading/" + path.substringAfterLast("gui/").replace('/', '_').removeSuffix(".png"))
            Minecraft.getInstance().textureManager.register(id, DynamicTexture({ id.toString() }, image))
            id
        }.getOrNull()
    }

    private fun white(a: Float) = ((a.coerceIn(0f, 1f) * 255).toInt() shl 24) or 0xFFFFFF
    private fun argb(a: Float, rgb: Int) = ((a.coerceIn(0f, 1f) * 255).toInt() shl 24) or (rgb and 0xFFFFFF)

    /**
     * Draws a texture of [pw] x [ph] screen pixels at GUI position x, y, one texture pixel per screen pixel.
     */
    private fun pixelBlit(g: GuiGraphicsExtractor, id: Identifier, x: Float, y: Float, pw: Int, ph: Int, scale: Float, color: Int) {
        val pose = g.pose()
        pose.pushMatrix()
        pose.translate(Math.round(x * scale) / scale, Math.round(y * scale) / scale)
        pose.scale(1f / scale, 1f / scale)
        g.blit(RenderPipelines.GUI_TEXTURED, id, 0, 0, 0f, 0f, pw, ph, pw, ph, color)
        pose.popMatrix()
    }

    /**
     * [alpha] is the overlay's own fade (1 while loading, falling to 0 as the game appears),
     * [progress] the smoothed loading progress 0..1.
     */
    @JvmStatic
    fun draw(g: GuiGraphicsExtractor, alpha: Float, progress: Float) {
        if (alpha <= 0.004f) return
        val w = g.guiWidth()
        val h = g.guiHeight()
        val scale = Minecraft.getInstance().window.guiScale.toFloat().coerceAtLeast(1f)

        // background: dark wine, a touch lighter at the top
        g.fill(0, 0, w, h, argb(alpha, BACKGROUND))
        val bands = 24
        for (i in 0 until bands) {
            val t = 1f - i / bands.toFloat()
            g.fill(0, h * i / (bands * 2), w, h * (i + 1) / (bands * 2), argb(alpha * 0.06f * t, 0x5A1A26))
        }

        val cx = w / 2f
        val emblemGui = (minOf(w, h) * 0.2f).coerceIn(40f, 90f)
        val wordGui = (emblemGui * 0.2f).coerceIn(9f, 16f)
        val gap = emblemGui * 0.22f
        val barGap = wordGui * 1.3f
        val total = emblemGui + gap + wordGui + barGap + 2f
        val top = h / 2f - total / 2f

        // soft rose glow behind the emblem
        texture("textures/gui/loading_glow.png")?.let {
            val gs = emblemGui * 3.2f
            val pose = g.pose()
            pose.pushMatrix()
            pose.translate(cx - gs / 2f, top + emblemGui / 2f - gs / 2f)
            pose.scale(gs / 256f, gs / 256f)
            g.blit(RenderPipelines.GUI_TEXTURED, it, 0, 0, 0f, 0f, 256, 256, 256, 256, white(alpha * 0.8f))
            pose.popMatrix()
        }

        // the emblem, pixel for pixel
        val ePx = emblemSizes.minBy { kotlin.math.abs(it - emblemGui * scale) }
        texture("textures/gui/emblem/$ePx.png")?.let {
            val gui = ePx / scale
            pixelBlit(g, it, cx - gui / 2f, top + (emblemGui - gui) / 2f, ePx, ePx, scale, white(alpha))
        }

        // MARCELINE
        val wordY = top + emblemGui + gap
        val hPx = wordSizes.minBy { kotlin.math.abs(it - wordGui * scale) }
        val wPx = Math.round(hPx * WORD_ASPECT)
        var wordW = wordGui * WORD_ASPECT
        texture("textures/gui/wordmark/$hPx.png")?.let {
            wordW = wPx / scale
            pixelBlit(g, it, cx - wordW / 2f, wordY, wPx, hPx, scale, white(alpha))
        }

        // thin gold progress line: a faint track that fades at both ends, the filled part brighter
        val barW = wordW * 1.05f
        val bx = cx - barW / 2f
        val by = wordY + wordGui + barGap
        val p = progress.coerceIn(0f, 1f)
        val steps = 48
        val px1 = 1f / scale
        for (k in 0 until steps) {
            val t0 = k / steps.toFloat()
            val edge = (1f - kotlin.math.abs(t0 * 2f - 1f)).let { kotlin.math.sqrt(it) }
            val filled = t0 < p
            val a = alpha * edge * (if (filled) 0.95f else 0.22f)
            val x0 = bx + barW * t0
            val x1 = bx + barW * (k + 1) / steps.toFloat()
            fillF(g, x0, by, x1, by + px1 * 2f, argb(a, GOLD))
        }
    }

    /** fill() with float edges, snapped to screen pixels. */
    private fun fillF(g: GuiGraphicsExtractor, x0: Float, y0: Float, x1: Float, y1: Float, color: Int) {
        val pose = g.pose()
        val s = Minecraft.getInstance().window.guiScale.toFloat().coerceAtLeast(1f)
        pose.pushMatrix()
        pose.scale(1f / s, 1f / s)
        g.fill(Math.round(x0 * s), Math.round(y0 * s), Math.round(x1 * s), Math.round(y1 * s).coerceAtLeast(Math.round(y0 * s) + 1), color)
        pose.popMatrix()
    }

    private const val GOLD = 0xC9A46A
    /** Width / height of the wordmark pictures. */
    private const val WORD_ASPECT = 12.186f
}
