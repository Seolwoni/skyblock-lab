package dev.marceline.websocket.packets

import dev.marceline.utils.dungeons.map.handlers.ScoreTracker
import dev.marceline.websocket.WebSocketPacket

object S2CPacketDungeonPrince: WebSocketPacket {
    override fun handle() = ScoreTracker::princeKilled.set(true)
}