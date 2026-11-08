package dev.riftwalk.websocket.packets

import dev.riftwalk.utils.dungeons.map.handlers.ScoreTracker
import dev.riftwalk.websocket.WebSocketPacket

object S2CPacketDungeonBat: WebSocketPacket {
    override fun handle() = ScoreTracker::batKilled.set(true)
}