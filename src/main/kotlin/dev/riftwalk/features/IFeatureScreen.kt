package dev.riftwalk.features

import net.minecraft.client.gui.screens.Screen

interface IFeatureScreen {
    fun createScreen(): Screen
}