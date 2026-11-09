package dev.marceline.event.impl

import dev.marceline.event.Event

class MouseClickEvent(val button: Int, val action: Int, val modifiers: Int): Event(true)