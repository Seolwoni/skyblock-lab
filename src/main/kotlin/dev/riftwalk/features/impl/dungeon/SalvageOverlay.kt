package dev.riftwalk.features.impl.dungeon

import dev.riftwalk.config.types.ColorSetting
import dev.riftwalk.event.impl.ContainerEvent
import dev.riftwalk.features.Feature
import dev.riftwalk.utils.ColorUtils.withAlpha
import dev.riftwalk.utils.PlayerUtils
import dev.riftwalk.utils.items.ItemUtils.customData
import dev.riftwalk.utils.items.ItemUtils.skyblockId
import dev.riftwalk.utils.location.LocationUtils
import dev.riftwalk.utils.render.Render2D.highlight
import java.awt.Color
import kotlin.jvm.optionals.getOrNull

object SalvageOverlay: Feature("Highlights salvageable dungeon gear.") {
    private val under50 by ColorSetting("Highlight Color", Color.CYAN.withAlpha(160))
    private val base50 by ColorSetting("50% stats Color", Color.RED.withAlpha(160))

    private val blacklist = setOf("ICE_SPRAY_WAND", "STARRED_ICE_SPRAY_WAND")

    override fun init() {
        register<ContainerEvent.Render.Slot.Pre> {
            if (! LocationUtils.inSkyblock) return@register
            val stack = event.slot.item.takeUnless { it.isEmpty } ?: return@register
            if (stack in PlayerUtils.getArmor()) return@register
            if (stack.skyblockId in blacklist) return@register
            if (stack.hoverName.string.contains("✪")) return@register
            val statBoost = stack.customData.getInt("baseStatBoostPercentage").getOrNull() ?: return@register
            event.slot.highlight(event.context, if (statBoost == 50) base50.value else under50.value, 1)
        }
    }
}