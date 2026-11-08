package dev.riftwalk.event.impl

import dev.riftwalk.event.Event

abstract class WebSocketEvent: Event(cancelable = false) {
    class Payload(val message: String): WebSocketEvent()
    object Connect: WebSocketEvent()
    object Disconnect: WebSocketEvent()
}