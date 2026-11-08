package dev.riftwalk.websocket.packets

import dev.riftwalk.utils.dungeons.map.handlers.DungeonScanner
import dev.riftwalk.websocket.WebSocketPacket

class S2CPacketRoomSecrets(val room: String, val secrets: Int): WebSocketPacket {
    override fun handle() {
        DungeonScanner.uniqueRooms[room]?.let {
            if (it.foundSecrets < secrets) it.foundSecrets = secrets
        }
    }
}