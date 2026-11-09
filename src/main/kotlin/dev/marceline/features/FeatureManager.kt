package dev.marceline.features

import dev.marceline.ui.clickgui.enums.CategoryType
import dev.marceline.ui.hud.HudElement
import dev.marceline.utils.render.RenderHelper.width

object FeatureManager {
    val features = mutableSetOf<Feature>()
    val hudElements = mutableListOf<HudElement>()

    fun getFeaturesByCategory(category: CategoryType) = features.filter { it.category == category }
    fun getFeatureByName(name: String) = features.find { it.jsonName == name }
    fun getHudByName(name: String) = hudElements.find { it.name == name }

    fun createFeatureList() = buildString {
        for ((category, features) in features.groupBy { it.category }.entries.sortedBy { it.key.ordinal }) {
            appendLine("Category: ${category.name}")
            for (feature in features.sortedByDescending { it.name.width() }) {
                appendLine("- ${feature.name}: ${feature.description.orEmpty()}")
            }
            appendLine()
        }
    }
}