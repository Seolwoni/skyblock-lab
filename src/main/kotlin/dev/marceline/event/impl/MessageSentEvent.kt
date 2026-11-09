package dev.marceline.event.impl

import dev.marceline.event.Event

class MessageSentEvent(var message: String, val isCommand: Boolean): Event(cancelable = true)