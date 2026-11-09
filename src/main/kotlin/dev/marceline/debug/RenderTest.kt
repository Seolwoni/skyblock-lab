package dev.marceline.debug

import dev.marceline.Marceline
import dev.marceline.event.EventBus
import dev.marceline.event.impl.DebugFlagEvent
import dev.marceline.event.impl.RenderWorldEvent
import dev.marceline.init.types.ISelfInit
import dev.marceline.utils.render.world.Render3D.renderBillboardedCircle
import dev.marceline.utils.render.world.Render3D.renderBlock
import dev.marceline.utils.render.world.Render3D.renderBox
import dev.marceline.utils.render.world.Render3D.renderBoxBounds
import dev.marceline.utils.render.world.Render3D.renderCircle
import dev.marceline.utils.render.world.Render3D.renderLine
import dev.marceline.utils.render.world.Render3D.renderString
import dev.marceline.utils.render.world.Render3D.renderTracer
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import java.awt.Color

object RenderTest: ISelfInit {
    private var pos: Vec3? = null

    override fun init() {
        EventBus.register<RenderWorldEvent> {
            if ("render" !in Marceline.debugFlags) return@register
            val player = Marceline.mc.player ?: return@register
            if (pos == null) pos = player.position()

            val base = pos ?: return@register
            val ctx = event.ctx

            event.ctx.renderString(
                text = "§6§lRender3D Showcase\n§fTesting all utilities",
                pos = base.add(0.0, 3.5, 0.0),
                scale = 1.2f
            )

            event.ctx.renderBillboardedCircle(
                base.add(0.0, 5.5, 0.0), 1, Color.RED
            )

            ctx.renderBlock(
                pos = BlockPos.containing(base),
                outlineColor = Color.WHITE,
                fillColor = Color(255, 255, 255, 60), // Semi-transparent white
                outline = true,
                fill = true,
                phase = false,
                lineWidth = 3.0
            )

            ctx.renderCircle(
                center = base.add(0.0, 0.05, 0.0),
                radius = 2.0,
                color = Color.RED,
                thickness = 3
            )

            val boxPos = base.add(4.0, 0.0, 0.0)
            ctx.renderBox(
                x = boxPos.x,
                y = boxPos.y,
                z = boxPos.z,
                width = 1.0,
                height = 2.0,
                outlineColor = Color.GREEN,
                fillColor = Color(0, 255, 0, 45)
            )

            event.ctx.renderString("renderBox()", boxPos.add(0.0, 2.2, 0.0), Color.GREEN, 0.8f)

            val boundsPos = base.add(- 4.0, 0.0, 0.0)
            ctx.renderBoxBounds(
                minX = boundsPos.x - 0.5,
                minY = boundsPos.y,
                minZ = boundsPos.z - 0.5,
                maxX = boundsPos.x + 0.5,
                maxY = boundsPos.y + 1.5,
                maxZ = boundsPos.z + 0.5,
                outlineColor = Color.BLUE,
                fillColor = Color(0, 0, 255, 45),
                phase = true,
                lineWidth = 2.0
            )

            event.ctx.renderString("renderBoxBounds() \n§e(Phase/Through Walls)", boundsPos.add(0.0, 1.8, 0.0), Color.BLUE, 0.8f)

            val lineStart = base.add(0.0, 1.0, - 4.0)
            val lineEnd = base.add(0.0, 2.5, - 4.0)
            ctx.renderLine(
                start = lineStart,
                finish = lineEnd,
                color = Color.MAGENTA,
                thickness = 4
            )
            event.ctx.renderString("renderLine()", lineEnd.add(0.0, 0.3, 0.0), Color.MAGENTA, 0.8f)

            val tracerTarget = base.add(0.0, 0.5, 4.0)
            ctx.renderTracer(
                point = tracerTarget,
                color = Color.YELLOW,
                thickness = 2.0
            )
            event.ctx.renderString("renderTracer()", tracerTarget.add(0.0, 1.0, 0.0), Color.YELLOW, 0.8f)
        }

        EventBus.register<DebugFlagEvent.Remove> { if (event.flag == "render") pos = null }
    }
}