package dev.marceline.features.impl.visual

import dev.marceline.event.impl.ChatMessageEvent
import dev.marceline.features.Feature
import dev.marceline.utils.dungeons.DungeonUtils
import dev.marceline.utils.location.LocationUtils
import dev.marceline.utils.render.Render2D.drawString
import dev.marceline.utils.render.RenderHelper.width
import kotlin.math.roundToInt

object WarpCooldown: Feature("Displays on screen how long until you can start another dungeon run.") {
    private const val CD = 30 // todo check if 15 on main
    private var startTime = System.currentTimeMillis()
    private var onCd = false

    override fun init() {
        hudElement("WarpCooldown", shouldDraw = ::onCd::get) { ctx, example ->
            val remaining = (CD - (System.currentTimeMillis() - startTime) / 1000.0).roundToInt()
            if (remaining < 0) onCd = false
            val text = "&bWarp Cooldown: &f${if (example) CD else remaining}s"
            ctx.drawString(text, 0, 0)
            return@hudElement text.width() to 9
        }

        register<ChatMessageEvent> {
            if (! LocationUtils.onHypixel) return@register
            if (onCd) return@register
            if (! event.unformattedText.matches(DungeonUtils.floorEnterRegex)) return@register
            startTime = System.currentTimeMillis()
            onCd = true
        }
    }
}