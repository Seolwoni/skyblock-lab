package dev.riftwalk.utils.render.world

import dev.riftwalk.Riftwalk.mc
import com.mojang.blaze3d.vertex.PoseStack
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.state.level.CameraRenderState

class RenderContext(val matrixStack: PoseStack, val camera: CameraRenderState, val collector: SubmitNodeCollector) {
    constructor(ctx: LevelRenderContext): this(ctx.poseStack(), mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState, ctx.submitNodeCollector())

    fun pose() = matrixStack.last()
}