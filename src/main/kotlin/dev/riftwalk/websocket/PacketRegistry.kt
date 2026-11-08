package dev.riftwalk.websocket

import dev.riftwalk.init.types.ISelfInit
import dev.riftwalk.websocket.packets.*

object PacketRegistry: ISelfInit {
    private val packets = mutableMapOf<String, Class<out WebSocketPacket>>()

    override fun init() {
        register<C2SPacketDungeonStart>("dungeon_start")
        register<S2CPacketChat>("chat")
        register<S2CPacketDungeonDoor>("dungeondoor")
        register<S2CPacketDungeonMimic>("dungeonmimic")
        register<S2CPacketDungeonPrince>("dungeonprince")
        register<S2CPacketDungeonBat>("dungeonbat")
        register<S2CPacketDungeonRoom>("dungeonroom")
        register<S2CPacketRoomSecrets>("dungeonroomsecrets")
        register<S2CPacketSocketInfo>("socket_info")
    }

    fun getClass(type: String) = packets[type]
    fun getType(obj: Any) = packets.entries.find { it.value == obj::class.java }?.key

    private inline fun <reified T: WebSocketPacket> register(type: String) = packets.set(type, T::class.java)
}