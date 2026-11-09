package dev.marceline.event.impl

import dev.marceline.event.Event
import dev.marceline.utils.ChatUtils.formattedText
import dev.marceline.utils.ChatUtils.unformattedText
import net.minecraft.network.chat.Component

class ActionBarMessageEvent(val component: Component): Event(cancelable = true) {
    val formattedText by lazy { component.formattedText }
    val unformattedText by lazy { component.unformattedText }

    var message = formattedText
}