package dev.riftwalk.event.impl

import dev.riftwalk.Riftwalk
import dev.riftwalk.event.Event

abstract class NoammDebugFlagEvent(val flag: String): Event(false) {
    class Add(flag: String): NoammDebugFlagEvent(flag)
    class Remove(flag: String): NoammDebugFlagEvent(flag)

    override fun cancel() {
        Riftwalk.debugFlags.remove(flag)
    }
}