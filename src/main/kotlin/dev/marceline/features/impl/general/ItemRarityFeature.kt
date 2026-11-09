package dev.marceline.features.impl.general

import dev.marceline.Marceline.MOD_ID
import dev.marceline.config.types.*
import dev.marceline.event.impl.ContainerEvent
import dev.marceline.features.Feature
import dev.marceline.utils.ColorUtils.withAlpha
import dev.marceline.utils.items.ItemRarity
import dev.marceline.utils.items.ItemUtils
import dev.marceline.utils.location.LocationUtils
import dev.marceline.utils.render.Render2D.checkSlot
import dev.marceline.utils.render.Render2D.drawBorder
import dev.marceline.utils.render.Render2D.drawTexture
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack

/**
 * @see dev.marceline.mixin.MixinGui
 */
object ItemRarityFeature: Feature(name = "Item Rarity", description = "Draws the rarity of item behind the slot.") {
    @JvmStatic val drawOnHotbar by ToggleSetting("Draw on Hotbar", true)
    private val rarityOpacity by SliderSetting("Rarity Opacity", 30f, 10f, 100f, 1f)
    private val style by DropdownSetting("Rarity Style", 0, listOf("Filled", "Outline", "Filled Outline", "Circle"))
    private val colorStyle by DropdownSetting("Color Style", 0, listOf("Default", "Hypixel"))
    private val circleTexture = Identifier.fromNamespaceAndPath(MOD_ID, "textures/gui/circle.png")

    override fun init() {
        register<ContainerEvent.Render.Slot.Pre> {
            onSlotDraw(event.context, event.slot.item, event.slot.x, event.slot.y)
        }
    }

    @JvmStatic
    fun onSlotDraw(ctx: GuiGraphicsExtractor, stack: ItemStack?, x: Int, y: Int) {
        if (! LocationUtils.inSkyblock) return
        if (stack == null || stack.isEmpty) return

        val rarity = ItemUtils.getRarity(stack)
        if (rarity == ItemRarity.NONE) return
        val rarityColor = if (colorStyle.value == 1) ItemRarity.getHypixelColor(rarity) else rarity.color
        val color = rarityColor.withAlpha(rarityOpacity.value / 100)

        if (checkSlot(x, y, - 1)) when (style.value) {
            0 -> ctx.fill(x, y, x + 16, y + 16, color.rgb)
            1 -> ctx.drawBorder(x, y, 16, 16, color)
            2 -> {
                ctx.fill(x, y, x + 16, y + 16, color.rgb)
                ctx.drawBorder(x, y, 16, 16, rarityColor)
            }

            3 -> ctx.drawTexture(circleTexture, x, y, 16, 16, color)
        }
    }
}