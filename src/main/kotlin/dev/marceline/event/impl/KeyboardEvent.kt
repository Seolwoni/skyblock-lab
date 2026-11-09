package dev.marceline.event.impl

import dev.marceline.event.Event
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent

abstract class KeyboardEvent: Event(cancelable = true) {
    class KeyPressed(val keyEvent: KeyEvent, val action: Int): KeyboardEvent()
    class CharTyped(val charEvent: CharacterEvent): KeyboardEvent()
}