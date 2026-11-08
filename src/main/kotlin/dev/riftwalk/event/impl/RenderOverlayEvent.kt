package dev.riftwalk.event.impl

import dev.riftwalk.event.Event
import net.minecraft.client.gui.GuiGraphicsExtractor

class RenderOverlayEvent(val context: GuiGraphicsExtractor): Event(cancelable = false)