package dev.marceline.features.impl.misc

import dev.marceline.config.types.ToggleSetting
import dev.marceline.event.impl.ChatMessageEvent
import dev.marceline.event.impl.RenderOverlayEvent
import dev.marceline.features.Feature
import dev.marceline.utils.ChatUtils
import dev.marceline.utils.ChatUtils.unformattedText
import dev.marceline.utils.NumbersUtils.toFixed
import dev.marceline.utils.location.LocationUtils.inSkyblock
import dev.marceline.utils.render.Render2D.drawCenteredString

object SBKick: Feature("Shows a timer on screen for when you can rejoin Skyblock.") {
    private val sendMsg by ToggleSetting("Send Party Message")

    private var showTime = false
    private var lastKickTime = System.currentTimeMillis()

    override fun init() {
        register<ChatMessageEvent> {
            when (event.component.unformattedText) {
                "There was a problem joining SkyBlock, try again in a moment!" -> {
                    if (showTime) return@register
                    lastKickTime = System.currentTimeMillis()
                    showTime = true
                }

                "A kick occurred in your connection, so you were put in the SkyBlock lobby!",
                "You were kicked while joining that server!" -> {
                    if (showTime) return@register
                    if (sendMsg.value) ChatUtils.sendPartyMessage("You were kicked while joining that server!")
                    lastKickTime = System.currentTimeMillis()
                    showTime = true
                }
            }
        }

        register<RenderOverlayEvent> {
            if (! showTime) return@register
            val timeSinceKick = System.currentTimeMillis() - lastKickTime
            if (inSkyblock && timeSinceKick > 10_000) {
                showTime = false
                return@register
            }

            if (timeSinceKick >= 60_000) showTime = false
            else event.context.drawCenteredString(
                "§cLast kicked from SkyBlock §b${(timeSinceKick / 1000.0).toFixed(2)}s ago",
                mc.window.guiScaledWidth / 2f,
                mc.window.guiScaledHeight / 2f - 20,
                scale = 1.5f
            )
        }
    }
}