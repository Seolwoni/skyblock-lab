package dev.riftwalk.websocket.packets

import dev.riftwalk.utils.dungeons.map.core.*
import dev.riftwalk.utils.dungeons.map.handlers.DungeonScanner
import dev.riftwalk.websocket.WebSocketPacket

class S2CPacketDungeonDoor(val x: Int, val z: Int, val col: Int, val row: Int, val doorType: DoorType): WebSocketPacket {
    override fun handle() {
        if (DungeonScanner.hasScanned) return
        val idx = row * 11 + col
        if (DungeonScanner.dungeonList[idx] !is Unknown) return
        DungeonScanner.setTile(idx, DoorTile(x, z, doorType))
    }
}