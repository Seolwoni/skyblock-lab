package dev.marceline.features.impl.dev

import dev.marceline.config.ConfigHolder
import dev.marceline.config.types.ButtonSetting
import dev.marceline.config.types.ColorCodeSetting
import dev.marceline.features.Feature
import net.minecraft.ChatFormatting

object ClassColors: Feature("Allows setting custom color for every dungeon class", toggled = true) {
    val archCode by ColorCodeSetting("Archer Code", ChatFormatting.DARK_RED).section("Colors")
    val bersCode by ColorCodeSetting("Berserk Code", ChatFormatting.GOLD)
    val healCode by ColorCodeSetting("Healer Code", ChatFormatting.DARK_PURPLE)
    val mageCode by ColorCodeSetting("Mage Code", ChatFormatting.DARK_AQUA)
    val tankCode by ColorCodeSetting("Tank Code", ChatFormatting.DARK_GREEN)
    val emptyCode = ColorCodeSetting("Empty Code", ChatFormatting.BLACK)

    private val reset by ButtonSetting("Reset Colors") {
        configSettings.forEach(ConfigHolder<*>::reset)
    }

    override fun toggle() = Unit
}