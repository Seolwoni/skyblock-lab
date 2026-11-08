package dev.riftwalk.websocket

interface WebSocketPacket {
    fun handle() = Unit
}