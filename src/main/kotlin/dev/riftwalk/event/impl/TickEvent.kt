package dev.riftwalk.event.impl

import dev.riftwalk.event.Event

abstract class TickEvent: Event(false) {
    object Start: TickEvent()
    object End: TickEvent()
    object Server: TickEvent()
}