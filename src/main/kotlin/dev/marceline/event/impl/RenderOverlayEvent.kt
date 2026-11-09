package dev.marceline.event.impl

import dev.marceline.event.Event
import net.minecraft.client.gui.GuiGraphicsExtractor

class RenderOverlayEvent(val context: GuiGraphicsExtractor): Event(cancelable = false)