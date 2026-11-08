package dev.riftwalk.features.impl.misc

import dev.riftwalk.features.Feature
import dev.riftwalk.utils.ChatUtils.unformattedText
import dev.riftwalk.utils.dungeons.enums.WitherRelic
import dev.riftwalk.utils.endsWithOneOf
import dev.riftwalk.utils.equalsOneOf
import dev.riftwalk.utils.items.ItemUtils.skyblockId
import dev.riftwalk.utils.location.LocationUtils
import dev.riftwalk.utils.startsWithOneOf
import net.minecraft.world.item.context.BlockPlaceContext

/**
 * @see dev.riftwalk.mixin.MixinBlockItem
 */
object NoItemPlace: Feature("Stops you from placing skull blocks/items.") {

    @JvmStatic
    fun placeHook(context: BlockPlaceContext): Boolean {
        if (! enabled) return false
        val item = context.player?.mainHandItem ?: return false
        val name = item.hoverName.unformattedText
        val id = item.skyblockId

        return when {
            LocationUtils.F7Phase == 5 && WitherRelic.fromName(name) != null -> true

            id.startsWithOneOf("ABIPHONE") -> true

            id.endsWithOneOf(
                "_TUBA",
                "_POWER_ORB",
                "_POCKET_BLACK_HOLE",
                "_FISHING_NET"
            ) -> true

            else -> id.equalsOneOf(
                "BOUQUET_OF_LIES",
                "FLOWER_OF_TRUTH",
                "BAT_WAND",
                "STARRED_BAT_WAND",
                "INFINITE_SPIRIT_LEAP",
                "ROYAL_PIGEON",
                "ARROW_SWAPPER",
                "JINGLE_BELLS",
                "FIRE_FREEZE_STAFF",
                "UMBERELLA",
                "ETHERWARP_CONDUIT",
                "KUUDRA_SHOP_ITEM"
            )
        }
    }
}