package dev.riftwalk.features.impl.visual

import dev.riftwalk.config.types.ColorSetting
import dev.riftwalk.config.types.ToggleSetting
import dev.riftwalk.features.Feature
import dev.riftwalk.utils.ColorUtils.toColor
import dev.riftwalk.utils.dungeons.enums.Blessing
import dev.riftwalk.utils.render.Render2D.drawString
import dev.riftwalk.utils.render.RenderHelper.width
import net.minecraft.ChatFormatting

object BlessingDisplay: Feature("Displays the current active blessings in the dungeon.") {
    private val power by ToggleSetting("Power Blessing", true).section("Blessings")
    private val time by ToggleSetting("Time Blessing", true)
    private val wisdom by ToggleSetting("Wisdom Blessing", false)
    private val life by ToggleSetting("Life Blessing", false)
    private val stone by ToggleSetting("Stone Blessing", false)

    private val powerColor by ColorSetting("Power Color", ChatFormatting.DARK_RED.toColor()).showIf { power.value }.section("Colors")
    private val timeColor by ColorSetting("Time Color", ChatFormatting.DARK_PURPLE.toColor()).showIf { time.value }
    private val wisdomColor by ColorSetting("Wisdom Color", ChatFormatting.AQUA.toColor()).showIf { wisdom.value }
    private val lifeColor by ColorSetting("Red Color", ChatFormatting.RED.toColor()).showIf { life.value }
    private val stoneColor by ColorSetting("Stone Color", ChatFormatting.GRAY.toColor()).showIf { stone.value }

    private fun getBlessingConfig(type: Blessing) = when (type) {
        Blessing.POWER -> power.value to powerColor.value
        Blessing.TIME -> time.value to timeColor.value
        Blessing.STONE -> stone.value to stoneColor.value
        Blessing.LIFE -> life.value to lifeColor.value
        Blessing.WISDOM -> wisdom.value to wisdomColor.value
    }

    override fun init() {
        hudElement("BlessingDisplay") { context, example ->
            var maxWidth = 0
            var currentY = 0

            Blessing.entries.forEach { blessing ->
                val (enabled, color) = getBlessingConfig(blessing)

                val value = if (example) 5 else blessing.current
                if (! enabled || value <= 0) return@forEach

                val text = "${blessing.displayString} §f$value"

                context.drawString(text, 0, currentY, color)

                maxWidth = maxOf(maxWidth, text.width())
                currentY += 9
            }

            return@hudElement maxWidth to currentY
        }
    }
}