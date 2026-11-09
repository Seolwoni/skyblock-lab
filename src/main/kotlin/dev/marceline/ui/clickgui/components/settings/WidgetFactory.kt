package dev.marceline.ui.clickgui.components.settings

import dev.marceline.config.ConfigHolder
import dev.marceline.config.types.*
import dev.marceline.ui.clickgui.components.settings.impl.*

object WidgetFactory {
    @Suppress("UNCHECKED_CAST")
    fun fromSetting(configHolder: ConfigHolder<*>) = when (configHolder) {
        is ToggleSetting -> ToggleWidget(configHolder)
        is DropdownSetting -> DropdownWidget(configHolder)
        is MultiCheckboxSetting -> MultiCheckboxWidget(configHolder)
        is ColorSetting -> ColorWidget(configHolder)
        is ColorCodeSetting -> ColorCodeWidget(configHolder)
        is KeybindSetting -> KeybindWidget(configHolder)
        is TextInputSetting -> TextInputWidget(configHolder)
        is SoundSetting -> SoundWidget(configHolder)
        is ButtonSetting -> ButtonWidget(configHolder)
        is SliderSetting -> SliderWidget(configHolder)
        is RangeSliderSetting<*> -> RangeSliderWidget(configHolder as RangeSliderSetting<Number>)
        is UnitSetting -> null
        is MapSetting<*, *> -> null
        is ListSetting<*> -> null
        else -> error("Unknown config holder type: ${configHolder::class.qualifiedName}")
    }
}