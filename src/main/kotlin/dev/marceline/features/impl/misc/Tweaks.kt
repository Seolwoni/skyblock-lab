package dev.marceline.features.impl.misc

import dev.marceline.config.types.ToggleSetting
import dev.marceline.features.Feature
import dev.marceline.utils.items.ItemUtils.skyblockId
import dev.marceline.utils.location.LocationUtils
import net.minecraft.world.item.ItemStack

/**
 * @see dev.marceline.mixin.MixinAbstractRecipeBookScreen
 * @see dev.marceline.mixin.MixinRecipeBookComponent
 * @see dev.marceline.mixin.MixinGuiGraphicsExtractor
 * @see dev.marceline.mixin.MixinPathCreation
 */
object Tweaks: Feature("Small quality of life tweaks.") {
    @JvmStatic val hideRecipeBook by ToggleSetting("Hide Recipe Book").withDescription("Hides the recipe book button in inventory GUIs.")
    @JvmStatic val closeRecipeBook by ToggleSetting("Close Recipe Book").withDescription("Also closes the recipe book screen.").showIf { hideRecipeBook.value }
    @JvmStatic val hideItemCooldowns by ToggleSetting("Hide Item Cooldowns").withDescription("Hides the hotbar cooldown overlay for items.")
    @JvmStatic val hideHotbarTooltips by ToggleSetting("Hide Hotbar Tooltips").withDescription("Hides the item tooltip when switching items in hotbar.")
    @JvmStatic val noAotvPaths by ToggleSetting("No AOTV Paths").withDescription("Prevents the Aspect of the Void from creating ghost paths and playing shovel sounds.")

    @JvmStatic fun shouldHideItemCooldownOverlay() = enabled && hideItemCooldowns.value && LocationUtils.inSkyblock
    @JvmStatic fun shouldPreventAotvPaths(stack: ItemStack) = enabled && noAotvPaths.value && LocationUtils.inSkyblock && stack.skyblockId == "ASPECT_OF_THE_VOID"
}