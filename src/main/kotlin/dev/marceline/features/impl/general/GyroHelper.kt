package dev.marceline.features.impl.general

import dev.marceline.config.types.ColorSetting
import dev.marceline.config.types.SliderSetting
import dev.marceline.config.types.ToggleSetting
import dev.marceline.event.impl.RenderWorldEvent
import dev.marceline.features.Feature
import dev.marceline.utils.ColorUtils.withAlpha
import dev.marceline.utils.MathUtils
import dev.marceline.utils.MathUtils.add
import dev.marceline.utils.MathUtils.toVec
import dev.marceline.utils.Utils
import dev.marceline.utils.WorldUtils
import dev.marceline.utils.items.ItemUtils.skyblockId
import dev.marceline.utils.render.world.Render3D.renderBox
import dev.marceline.utils.render.world.Render3D.renderCircle
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