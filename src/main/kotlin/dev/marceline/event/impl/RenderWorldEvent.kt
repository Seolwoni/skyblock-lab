package dev.marceline.event.impl

import dev.marceline.event.Event
import dev.marceline.utils.render.world.RenderContext

class RenderWorldEvent(val ctx: RenderContext): Event(cancelable = false)