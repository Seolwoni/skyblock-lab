package dev.marceline.websocket.packets

import dev.marceline.utils.dungeons.map.handlers.ScoreTracker
import dev.marceline.websocket.WebSocketPacket

object S2CPacketDungeonMimic: WebSocketPacket {
    override fun handle() = ScoreTracker::mimicKilled.set(true)
}