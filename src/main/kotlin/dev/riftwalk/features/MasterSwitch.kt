package dev.riftwalk.features

import dev.riftwalk.config.ConfigManager
import dev.riftwalk.features.impl.dev.ClickGui
import dev.riftwalk.ui.notification.NotificationManager

/**
 * Riftwalk: turns every feature off at once and back on to exactly the ones that were on.
 * The list of what was on is saved in the config, so it survives a restart while everything is off.
 */
object MasterSwitch {
    val allOff get() = ClickGui.masterSaved.value.isNotEmpty()

    fun toggle() {
        if (allOff) turnOn() else turnOff()
        ConfigManager.save()
    }

    private fun turnOff() {
        val on = FeatureManager.features.filter { it.enabled && it !is ClickGui }
        if (on.isEmpty()) return NotificationManager.push("Riftwalk", "&7Nothing is on")
        on.forEach { dev.riftwalk.utils.Safely("MasterSwitch ${it.name}") { it.toggle() } }
        // Some built-in parts (server link, door fix, class colours, mod hider) can't be switched off: skip those.
        val turnedOff = on.filter { ! it.enabled }
        ClickGui.masterSaved.value = turnedOff.joinToString("\n") { it.jsonName }
        NotificationManager.push("Riftwalk", "&cEverything off &7(${turnedOff.size} features)")
    }

    private fun turnOn() {
        val names = ClickGui.masterSaved.value.split("\n").filter { it.isNotBlank() }.toSet()
        var n = 0
        FeatureManager.features.filter { it.jsonName in names && ! it.enabled }.forEach {
            dev.riftwalk.utils.Safely("MasterSwitch ${it.name}") { it.toggle() }
            n++
        }
        ClickGui.masterSaved.value = ""
        NotificationManager.push("Riftwalk", "&aBack on &7($n features)")
    }
}
