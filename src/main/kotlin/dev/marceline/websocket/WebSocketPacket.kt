package dev.marceline.websocket

interface WebSocketPacket {
    fun handle() = Unit
}