package dev.riftwalk.utils.dungeons.enums

import dev.riftwalk.config.types.ColorCodeSetting
import dev.riftwalk.features.impl.dev.ClassColors
import dev.riftwalk.utils.ColorUtils.toColor

enum class DungeonClass(val setting: ColorCodeSetting) {
    Archer(ClassColors.archCode),
    Berserk(ClassColors.bersCode),
    Healer(ClassColors.healCode),
    Mage(ClassColors.mageCode),
    Tank(ClassColors.tankCode),
    Empty(ClassColors.emptyCode);

    val color get() = setting.value.toColor()
    val code get() = setting.value.toString()

    companion object {
        fun fromName(name: String) = entries.find {
            it.name.equals(name, ignoreCase = true)
        } ?: Empty
    }
}