package dev.riftwalk.features

import dev.riftwalk.Riftwalk

interface Shortcuts {
    val Shortcuts.mc get() = Riftwalk.mc
    val Shortcuts.scope get() = Riftwalk.scope
    val Shortcuts.player get() = requireNotNull(mc.player) { "tried to access player before it was loaded" }
    val Shortcuts.level get() = requireNotNull(mc.level) { "tried to access level before world was loaded" }
    val Shortcuts.gameMode get() = requireNotNull(mc.gameMode) { "mc.gameMode is null" }
}