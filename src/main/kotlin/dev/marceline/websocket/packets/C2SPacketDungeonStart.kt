package dev.marceline.websocket.packets

import dev.marceline.websocket.WebSocketPacket

class C2SPacketDungeonStart(
    val serverId: String,
    val floor: String,
    val members: List<String>,
    val entrance: Pair<Int, Int>
): WebSocketPacket