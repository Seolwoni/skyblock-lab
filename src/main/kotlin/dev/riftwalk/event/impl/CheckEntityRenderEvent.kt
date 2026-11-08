package dev.riftwalk.event.impl

import dev.riftwalk.event.Event
import net.minecraft.world.entity.Entity

class CheckEntityRenderEvent(val entity: Entity): Event(cancelable = true)