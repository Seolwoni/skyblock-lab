package dev.marceline.websocket.packets

import dev.marceline.utils.dungeons.map.handlers.ScoreTracker
import dev.marceline.websocket.WebSocketPacket

object S2CPacketDungeonBat: WebSocketPacket {
    override fun handle() = ScoreTracker::batKilled.set(true)
}