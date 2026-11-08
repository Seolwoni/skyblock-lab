package dev.riftwalk.websocket.packets

import dev.riftwalk.event.EventBus
import dev.riftwalk.event.impl.ChatMessageEvent
import dev.riftwalk.utils.ChatUtils
import dev.riftwalk.utils.ChatUtils.removeFormatting
import dev.riftwalk.utils.PartyUtils
import dev.riftwalk.utils.location.LocationUtils
import dev.riftwalk.websocket.WebSocketPacket
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