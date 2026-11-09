package dev.marceline.event.impl

import dev.marceline.event.Event
import net.minecraft.world.entity.Entity

class EntityUnloadEvent(val entity: Entity): Event(false)