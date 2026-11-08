package dev.riftwalk.event.impl

import dev.riftwalk.Riftwalk
import dev.riftwalk.event.Event

abstract class DebugFlagEvent(val flag: String): Event(false) {
    class Add(flag: String): DebugFlagEvent(flag)
    class Remove(flag: String): DebugFlagEvent(flag)

    override fun cancel() {
        Riftwalk.debugFlags.remove(flag)
    }
}