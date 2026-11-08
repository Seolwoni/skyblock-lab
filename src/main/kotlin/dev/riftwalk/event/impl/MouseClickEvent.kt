package dev.riftwalk.event.impl

import dev.riftwalk.event.Event

class MouseClickEvent(val button: Int, val action: Int, val modifiers: Int): Event(true)