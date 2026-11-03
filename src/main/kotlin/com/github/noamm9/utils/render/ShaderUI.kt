package com.github.noamm9.utils.render

import com.github.noamm9.NoammAddons
import com.github.noamm9.init.types.ISelfInit
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.state.gui.GuiElementRenderState
import net.minecraft.resources.Identifier
import org.joml.Matrix3x2f
import java.awt.Color
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Shader-drawn GUI shapes: rounded corners with a one-pixel anti-aliased edge (rw_corner.fsh),
 * so rounded panels, pills and dots look smooth at any size and GUI scale instead of stepped.
 * The straight parts are ordinary fills; only the four corners go through the shader.
 */
object ShaderUI: ISelfInit {
    private fun id(path: String) = Identifier.fromNamespaceAndPath(NoammAddons.MOD_ID, path)

    private val CORNER by lazy {
        RenderPipelines.register(
            com.github.noamm9.compat.RenderPipeline.builder(RenderPipelines.GUI_SNIPPET).apply {
                withLocation(id("pipeline/rw_corner"))
                withVertexShader(id("core/rw_corner"))
                withFragmentShader(id("core/rw_corner"))
                withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
            }.build()
        )
    }

    /** Off when the shader failed once, so the old stepped corners are used from then on. */
    @JvmField var available = true

    override fun init() {
        runCatching { CORNER }.onFailure {
            available = false
            NoammAddons.logger.error("Riftwalk: rounded corner shader unavailable, using stepped corners", it)
        }
    }

    /**
     * One quarter-circle corner: the square ([x],[y]) .. ([x]+[r], [y]+[r]); ([cx],[cy]) is the corner of that square
     * where the circle's centre is (one of x / x+r and y / y+r).
     */
    private fun corner(ctx: GuiGraphicsExtractor, x: Float, y: Float, r: Float, cx: Float, cy: Float, color: Int) {
        val pose = Matrix3x2f(ctx.pose())
        val scissor = ctx.scissorStack.peek()
        ctx.guiRenderState.addGuiElement(object: GuiElementRenderState {
            override fun buildVertices(buffer: VertexConsumer) {
                fun v(px: Float, py: Float) = buffer.addVertexWith2DPose(pose, px, py).setUv((px - cx) / r, (py - cy) / r).setColor(color)
                v(x, y)
                v(x, y + r)
                v(x + r, y + r)
                v(x + r, y)
            }

            override fun pipeline() = CORNER
            override fun textureSetup(): TextureSetup = TextureSetup.noTexture()
            override fun scissorArea(): ScreenRectangle? = scissor
            override fun bounds(): ScreenRectangle? = ScreenRectangle(floor(x).toInt() - 1, floor(y).toInt() - 1, ceil(r).toInt() + 2, ceil(r).toInt() + 2)
                .transformMaxBounds(pose).let { b -> if (scissor != null) b.intersection(scissor) else b }
        })
    }

    /** Rounded rectangle with smooth corners. Returns false (draw nothing) if the shader isn't available. */
    fun roundRect(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, radius: Float, color: Color): Boolean {
        if (! available) return false
        if (w <= 0f || h <= 0f || color.alpha == 0) return true
        val r = radius.coerceAtMost(minOf(w, h) / 2f).coerceAtLeast(0f)
        val argb = color.rgb
        return runCatching {
            if (r < 0.75f) {
                fillF(ctx, x, y, w, h, argb)
                return@runCatching
            }
            // middle column and the two side strips
            fillF(ctx, x + r, y, w - 2 * r, h, argb)
            fillF(ctx, x, y + r, r, h - 2 * r, argb)
            fillF(ctx, x + w - r, y + r, r, h - 2 * r, argb)
            corner(ctx, x, y, r, x + r, y + r, argb)
            corner(ctx, x + w - r, y, r, x + w - r, y + r, argb)
            corner(ctx, x, y + h - r, r, x + r, y + h - r, argb)
            corner(ctx, x + w - r, y + h - r, r, x + w - r, y + h - r, argb)
        }.onFailure {
            available = false
            NoammAddons.logger.error("Riftwalk: rounded corner shader failed, using stepped corners", it)
        }.isSuccess
    }

    /** A fill at fractional coordinates (the pose carries the fraction, like Render2D.drawRect). */
    private fun fillF(ctx: GuiGraphicsExtractor, x: Float, y: Float, w: Float, h: Float, argb: Int) {
        if (w <= 0f || h <= 0f) return
        ctx.pose().pushMatrix()
        ctx.pose().translate(x, y)
        ctx.pose().scale(w, h)
        ctx.fill(0, 0, 1, 1, argb)
        ctx.pose().popMatrix()
    }
}
