package dev.marceline.features.impl.dungeon

import dev.marceline.config.types.ToggleSetting
import dev.marceline.event.impl.ChatMessageEvent
import dev.marceline.event.impl.MainThreadPacketReceivedEvent
import dev.marceline.features.Feature
import dev.marceline.utils.ChatUtils
import dev.marceline.utils.ChatUtils.removeFormatting
import dev.marceline.utils.SoundUtils
import dev.marceline.utils.items.ItemUtils.lore
import dev.marceline.utils.items.ItemUtils.skyblockId
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.sounds.SoundEvents
import kotlin.math.roundToInt

object Ragnarock: Feature("Ragnarock alerts") {
    private val alertCancelled by ToggleSetting("Alert Cancelled", true).withDescription("plays a sound when the Ragnarock is cancelled")
    private val strengthGainedMessage by ToggleSetting("Strength Gained", true).withDescription("Prints in chat how much strength you gained from the Ragnarock")

    private val cancelRegex = Regex("Ragnarock was cancelled due to (?:being hit|taking damage)!")
    private val strengthRegex = Regex("Strength: \\+(\\d+)")

    override fun init() {
        register<MainThreadPacketReceivedEvent.Pre> {
            if (! strengthGainedMessage.value) return@register
            val packet = event.packet as? ClientboundSoundPacket ?: return@register
            if (packet.sound.value().location.path != "entity.wolf.death") return@register
            if (packet.pitch.toDouble() == 1.4920635) return@register
            if (player.mainHandItem.skyblockId != "RAGNAROCK_AXE") return@register
            val strengthLine = player.mainHandItem.lore.map { it.removeFormatting() }.find { it.startsWith("Strength:") } ?: return@register
            val match = strengthRegex.find(strengthLine) ?: return@register
            val baseStrength = match.groupValues[1].toIntOrNull() ?: return@register
            ChatUtils.modMessage("&fGained strength: &c${(baseStrength * 1.5).roundToInt()}")
        }

        register<ChatMessageEvent> {
            if (! alertCancelled.value) return@register
            if (! event.unformattedText.matches(cancelRegex)) return@register
            ChatUtils.showTitle(subtitle = "&cRagnarock Cancelled")
            SoundUtils.playSound(SoundEvents.NOTE_BLOCK_PLING, 0.25f, 1f)
        }
    }
}