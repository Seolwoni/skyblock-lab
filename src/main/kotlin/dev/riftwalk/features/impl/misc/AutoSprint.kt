package dev.riftwalk.features.impl.misc

import dev.riftwalk.config.types.ToggleSetting
import dev.riftwalk.event.impl.TickEvent
import dev.riftwalk.features.Feature

object AutoSprint: Feature("Automatically sprint for you.") {
    private val disableInWater by ToggleSetting("Disable In Water").withDescription("Stops sprinting while you are in water.")

    override fun init() {
        register<TickEvent.Start> {
            if (mc.gui.screen() != null) return@register
            if (player.isSprinting) return@register
            if (disableInWater.value && player.isInWater) return@register
            mc.options.keySprint.isDown = true
        }
    }
}