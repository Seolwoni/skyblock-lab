package dev.riftwalk.event.impl

import dev.riftwalk.event.Event
import net.minecraft.network.chat.Component

class BossBarUpdateEvent(val name: Component, val progress: Float): Event(false)