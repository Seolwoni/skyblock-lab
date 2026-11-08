package dev.riftwalk.features.impl.general

import dev.riftwalk.config.types.ColorSetting
import dev.riftwalk.config.types.SliderSetting
import dev.riftwalk.config.types.ToggleSetting
import dev.riftwalk.event.impl.RenderWorldEvent
import dev.riftwalk.features.Feature
import dev.riftwalk.utils.ColorUtils.withAlpha
import dev.riftwalk.utils.MathUtils
import dev.riftwalk.utils.MathUtils.add
import dev.riftwalk.utils.MathUtils.toVec
import dev.riftwalk.utils.Utils
import dev.riftwalk.utils.WorldUtils
import dev.riftwalk.utils.items.ItemUtils.skyblockId
import dev.riftwalk.utils.render.world.Render3D.renderBox
import dev.riftwalk.utils.render.world.Render3D.renderCircle
import net.minecraft.tags.BlockTags

object GyroHelper: Feature("Renders a circle where your gyro will be located.", "Gyro Helper") {
    private val drawBox by ToggleSetting("Draw Box", true).withDescription("Draws a box in the middle of the Gyrokinetic Wand sucking range").section("Render")
    private val drawRing by ToggleSetting("Draw Sucking Range", true).withDescription("Draws the sucking range of the Gyrokinetic Wand")
    private val lineWidth by SliderSetting("Ring Width", 2, 1, 10, 1).withDescription("Controls the thickness of the ring")

    private val boxColor by ColorSetting("Box Color", Utils.favoriteColor.withAlpha(0.3f)).section("Color")
    private val ringColor by ColorSetting("Ring Color", Utils.favoriteColor)

    override fun init() {
        register<RenderWorldEvent> {
            if (! drawRing.value && ! drawBox.value) return@register
            if (boxColor.value.alpha + ringColor.value.alpha == 0) return@register
            if (player.mainHandItem.skyblockId != "GYROKINETIC_WAND") return@register
            val gyroPos = MathUtils.raytrace(player, 25) ?: return@register
            val stateAtPos = WorldUtils.getStateAt(gyroPos)
            val stateAbove = WorldUtils.getStateAt(gyroPos.above())

            if (stateAtPos.isAir || (! stateAbove.isAir && ! stateAbove.`is`(BlockTags.WOOL_CARPETS))) return@register

            if (drawBox.value) event.ctx.renderBox(gyroPos.x + 0.5, gyroPos.y, gyroPos.z + 0.5, 1, 1, boxColor.value)
            if (drawRing.value) {
                val center = gyroPos.add(0.5, 2.05, 0.5).toVec()
                event.ctx.renderCircle(center, 10.0, ringColor.value, lineWidth.value)
            }
        }
    }
}