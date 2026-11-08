package dev.riftwalk.websocket.packets

import dev.riftwalk.utils.dungeons.map.handlers.ScoreTracker
import dev.riftwalk.websocket.WebSocketPacket

object S2CPacketDungeonMimic: WebSocketPacket {
    override fun handle() = ScoreTracker::mimicKilled.set(true)
}