package dev.marceline.websocket.packets

import dev.marceline.event.EventBus
import dev.marceline.event.impl.ChatMessageEvent
import dev.marceline.utils.ChatUtils
import dev.marceline.utils.ChatUtils.removeFormatting
import dev.marceline.utils.PartyUtils
import dev.marceline.utils.location.LocationUtils
import dev.marceline.websocket.WebSocketPacket
import net.minecraft.network.chat.Component

class S2CPacketChat(val message: String): WebSocketPacket {
    override fun handle() {
        ChatUtils.chat("§b[WS]§r $message")
        if (! LocationUtils.inDungeon) return
        val text = message.removeFormatting()
        if (! PartyUtils.isMember(text.substringBefore(":"))) return
        EventBus.post(ChatMessageEvent(Component.literal("Party > $text")))
    }
}