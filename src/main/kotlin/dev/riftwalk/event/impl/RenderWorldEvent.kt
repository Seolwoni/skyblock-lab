package dev.riftwalk.event.impl

import dev.riftwalk.event.Event
import dev.riftwalk.utils.render.world.RenderContext

class RenderWorldEvent(val ctx: RenderContext): Event(cancelable = false)