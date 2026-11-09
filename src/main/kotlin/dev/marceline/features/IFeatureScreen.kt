package dev.marceline.features

import net.minecraft.client.gui.screens.Screen

interface IFeatureScreen {
    fun createScreen(): Screen
}