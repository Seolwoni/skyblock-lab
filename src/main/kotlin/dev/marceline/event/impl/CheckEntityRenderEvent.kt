package dev.marceline.event.impl

import dev.marceline.event.Event
import net.minecraft.world.entity.Entity

class CheckEntityRenderEvent(val entity: Entity): Event(cancelable = true)