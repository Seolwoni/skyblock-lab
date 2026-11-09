package dev.marceline.event.impl

import dev.marceline.event.Event

abstract class TickEvent: Event(false) {
    object Start: TickEvent()
    object End: TickEvent()
    object Server: TickEvent()
}