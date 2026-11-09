package dev.marceline.websocket.packets

import dev.marceline.utils.dungeons.map.handlers.DungeonScanner
import dev.marceline.websocket.WebSocketPacket

class S2CPacketRoomSecrets(val room: String, val secrets: Int): WebSocketPacket {
    override fun handle() {
        DungeonScanner.uniqueRooms[room]?.let {
            if (it.foundSecrets < secrets) it.foundSecrets = secrets
        }
    }
}