package dev.riftwalk.event.impl

import dev.riftwalk.event.Event
import net.minecraft.world.entity.Entity

class EntityUnloadEvent(val entity: Entity): Event(false)