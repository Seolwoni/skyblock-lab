package dev.riftwalk.websocket.packets

import dev.riftwalk.utils.dungeons.map.handlers.ScoreTracker
import dev.riftwalk.websocket.WebSocketPacket

object S2CPacketDungeonPrince: WebSocketPacket {
    override fun handle() = ScoreTracker::princeKilled.set(true)
}