package dev.riftwalk.event.impl

import dev.riftwalk.event.Event
import dev.riftwalk.utils.ChatUtils.formattedText
import dev.riftwalk.utils.ChatUtils.unformattedText
import net.minecraft.network.chat.Component

class PrintMessageEvent(val component: Component): Event(cancelable = true) {
    val formattedText by lazy { component.formattedText }
    val unformattedText by lazy { component.unformattedText }
}