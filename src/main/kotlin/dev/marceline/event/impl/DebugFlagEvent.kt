package dev.marceline.event.impl

import dev.marceline.Marceline
import dev.marceline.event.Event

abstract class DebugFlagEvent(val flag: String): Event(false) {
    class Add(flag: String): DebugFlagEvent(flag)
    class Remove(flag: String): DebugFlagEvent(flag)

    override fun cancel() {
        Marceline.debugFlags.remove(flag)
    }
}