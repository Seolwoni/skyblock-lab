package dev.riftwalk.event.impl

import dev.riftwalk.event.Event
import dev.riftwalk.features.impl.floor7.terminals.impl.Terminal
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket

abstract class TerminalEvent(): Event(cancelable = false) {
    class Open(val handler: Terminal): TerminalEvent()
    object Close: TerminalEvent()
    class SlotUpdate(val handler: Terminal, val packet: ClientboundContainerSetSlotPacket): TerminalEvent()
    class Break(val handler: Terminal): TerminalEvent()
}