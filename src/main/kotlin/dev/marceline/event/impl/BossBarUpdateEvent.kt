package dev.marceline.event.impl

import dev.marceline.event.Event
import net.minecraft.network.chat.Component

class BossBarUpdateEvent(val name: Component, val progress: Float): Event(false)