package com.github.noamm9.features

import com.github.noamm9.config.ConfigManager
import com.github.noamm9.features.impl.dev.ClickGui
import com.github.noamm9.ui.notification.NotificationManager

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
        on.forEach { runCatching { it.toggle() } }
        ClickGui.masterSaved.value = on.joinToString("\n") { it.jsonName }
        NotificationManager.push("Riftwalk", "&cEverything off &7(${on.size} features)")
    }

    private fun turnOn() {
        val names = ClickGui.masterSaved.value.split("\n").filter { it.isNotBlank() }.toSet()
        var n = 0
        FeatureManager.features.filter { it.jsonName in names && ! it.enabled }.forEach {
            runCatching { it.toggle() }
            n++
        }
        ClickGui.masterSaved.value = ""
        NotificationManager.push("Riftwalk", "&aBack on &7($n features)")
    }
}
