package dev.riftwalk.event.impl

import dev.riftwalk.event.Event

class MessageSentEvent(var message: String, val isCommand: Boolean): Event(cancelable = true)