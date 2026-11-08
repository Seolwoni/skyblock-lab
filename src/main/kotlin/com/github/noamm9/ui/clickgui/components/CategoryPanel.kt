package com.github.noamm9.ui.clickgui.components

import com.mojang.blaze3d.platform.InputConstants

import com.github.noamm9.NoammAddons.MOD_NAME
import com.github.noamm9.features.Feature
import com.github.noamm9.features.FeatureManager
import com.github.noamm9.features.IFeatureScreen
import com.github.noamm9.features.impl.dev.ClickGui
import com.github.noamm9.ui.clickgui.*
import com.github.noamm9.ui.clickgui.components.settings.Style
import com.github.noamm9.ui.clickgui.enums.CategoryType
import com.github.noamm9.ui.notification.NotificationManager
import com.github.noamm9.ui.utils.Animation
import com.github.noamm9.utils.ColorUtils.withAlpha
import com.github.noamm9.utils.GuiUtils
import com.github.noamm9.utils.render.Render2D.drawCenteredString
import com.github.noamm9.utils.render.Render2D.drawRect
import com.github.noamm9.utils.render.Render2D.drawString
import com.github.noamm9.utils.render.Render2D.drawHorizontalGradient
import com.github.noamm9.utils.render.Render2D.drawVerticalGradient
import com.github.noamm9.utils.render.Render2D.scissor
import com.github.noamm9.utils.render.RenderHelper.width
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Color

class CategoryPanel(val category: CategoryType, var x: Int, var y: Int, private val screen: ClickGuiScreen) {
    private companion object {
        const val WIDTH = 110
        const val HEADER_HEIGHT = 22
        const val BUTTON_HEIGHT = 16
        const val MAX_DISPLAY_HEIGHT = 350

        private val headerBg = Color(32, 22, 50, 240)
        private val bodyBg = Color(15, 11, 23, 215)
        private val hoverColor = Color(255, 255, 255, 18)
    }

    private val features = FeatureManager.getFeaturesByCategory(category)
    private var sorted: Collection<Feature> = emptyList()
    private var cq: String? = null
    private var lc = - 1

    private val openAnim = Animation(150)
    var collapsed = false

    private var scrollOffset = 0f
    var dragging = false
    private var dragX = 0
    private var dragY = 0

    fun render(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        if (dragging) {
            x = mouseX - dragX
            y = mouseY - dragY
        }

        val filteredFeatures = getSorting()
        if (filteredFeatures.isEmpty() && screen.searchQuery.isNotEmpty()) return

        openAnim.update(if (collapsed && features.size == filteredFeatures.size) 0f else 1f)

        Style.drawShadow(context, x.toFloat(), y.toFloat(), WIDTH.toFloat(), HEADER_HEIGHT.toFloat(), 4f, 0.6f)
        Style.roundRect(context, x.toFloat(), y.toFloat(), WIDTH.toFloat(), HEADER_HEIGHT.toFloat(), 4f, headerBg)
        context.drawRect(x, y + HEADER_HEIGHT - 4, WIDTH, 4, headerBg)
        Style.gradientPill(context, x.toFloat(), (y + HEADER_HEIGHT - 1).toFloat(), WIDTH.toFloat(), 1f, Style.accentColor, Style.accent2)
        val title = "§l${category.title}"
        val iconColor = Style.mix(Style.accentColor, Style.accent2, category.ordinal / (CategoryType.entries.size - 1f))
        val tw = title.width()
        val left = x + WIDTH / 2f - (tw + 13f) / 2f
        com.github.noamm9.ui.clickgui.SkyHanniScreen.drawCategoryIcon(context, category, left, y + 6.5f, 9f, iconColor)
        context.drawString(title, left + 13f, y + 7, Color(237, 231, 255))

        if (openAnim.value > 0.01f || features.size != filteredFeatures.size) {
            val totalContentHeight = filteredFeatures.size * BUTTON_HEIGHT
            val visibleHeight = totalContentHeight.coerceAtMost(MAX_DISPLAY_HEIGHT)
            val currentScissorHeight = (visibleHeight * openAnim.value).toInt()
            val maxScroll = (totalContentHeight - visibleHeight).coerceAtLeast(0)
            if (scrollOffset > maxScroll) scrollOffset = maxScroll.toFloat()
            if (scrollOffset < 0) scrollOffset = 0f

            var currentY = y + HEADER_HEIGHT - scrollOffset.toInt()

            context.scissor(x, y + HEADER_HEIGHT, WIDTH, currentScissorHeight)

            filteredFeatures.forEach { feature ->
                if (currentY + BUTTON_HEIGHT > y + HEADER_HEIGHT && currentY < y + HEADER_HEIGHT + visibleHeight) {
                    val isHovered = mouseX >= x && mouseX <= x + WIDTH &&
                        mouseY >= currentY && mouseY <= currentY + BUTTON_HEIGHT &&
                        mouseY >= y + HEADER_HEIGHT && mouseY <= y + HEADER_HEIGHT + visibleHeight

                    context.drawRect(x, currentY, WIDTH, BUTTON_HEIGHT, bodyBg)

                    if (feature.enabled) {
                        context.drawHorizontalGradient(x, currentY, WIDTH, BUTTON_HEIGHT, Style.accentColor.withAlpha(110), Style.accent2.withAlpha(40))
                        context.drawRect(x, currentY, 2, BUTTON_HEIGHT, Style.accentColor)
                    }

                    if (isHovered) {
                        context.drawRect(x, currentY, WIDTH, BUTTON_HEIGHT, hoverColor)
                    }

                    context.drawCenteredString(feature.name, x + WIDTH / 2, currentY + 4, if (feature.enabled) Color.WHITE else Color(196, 184, 224))

                    if (isHovered && ! screen.isMouseOverConfigWindow(mouseX, mouseY)) {
                        TooltipManager.hover(feature.description, mouseX, mouseY)
                    }
                }
                currentY += BUTTON_HEIGHT
            }
            context.disableScissor()

            if (maxScroll > 0 && ! collapsed) {
                val barHeight = (visibleHeight.toFloat() / totalContentHeight.toFloat()) * visibleHeight
                val barY = (y + HEADER_HEIGHT) + ((scrollOffset / maxScroll) * (visibleHeight - barHeight))

                context.drawVerticalGradient(x + WIDTH - 2, barY, 2, barHeight, Style.accentColor, Style.accent2)
            }
        }
    }

    fun handleScroll(delta: Double) {
        if (collapsed) return
        val filteredFeatures = getSorting()
        val totalContentHeight = filteredFeatures.size * BUTTON_HEIGHT
        val visibleHeight = totalContentHeight.coerceAtMost(MAX_DISPLAY_HEIGHT)

        if (totalContentHeight <= visibleHeight) return

        scrollOffset -= (delta * 15).toFloat()
    }

    fun isMouseOver(mx: Int, my: Int): Boolean {
        val filteredFeatures = getSorting()
        val totalContentHeight = filteredFeatures.size * BUTTON_HEIGHT
        val visibleHeight = totalContentHeight.coerceAtMost(MAX_DISPLAY_HEIGHT)

        return mx >= x && mx <= x + WIDTH && my >= y && my <= y + HEADER_HEIGHT + visibleHeight
    }

    fun isMouseOverHeader(mx: Double, my: Double): Boolean {
        return mx >= x && mx <= x + WIDTH && my >= y && my <= y + HEADER_HEIGHT
    }

    fun mouseClicked(mouseX: Double, mouseY: Double, button: Int) {
        if (isMouseOverHeader(mouseX, mouseY)) {
            if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                dragging = true
                dragX = (mouseX - x).toInt()
                dragY = (mouseY - y).toInt()
            }
            else if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
                collapsed = ! collapsed
                Style.playClickSound(if (collapsed) 0.8f else 1.1f)
            }
            return
        }

        val filteredFeatures = getSorting()
        if (collapsed && features.size == filteredFeatures.size) return

        val totalContentHeight = filteredFeatures.size * BUTTON_HEIGHT
        val visibleHeight = totalContentHeight.coerceAtMost(MAX_DISPLAY_HEIGHT)

        if (mouseY < y + HEADER_HEIGHT || mouseY > y + HEADER_HEIGHT + visibleHeight) return

        var currentY = y + HEADER_HEIGHT - scrollOffset.toInt()

        filteredFeatures.forEach { feature ->
            if (mouseX >= x && mouseX <= x + WIDTH && mouseY >= currentY && mouseY <= currentY + BUTTON_HEIGHT) {
                if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                    feature.toggle()
                    return
                }
                else if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
                    openFeature(feature)
                    return
                }
            }
            currentY += BUTTON_HEIGHT
        }
    }

    fun mouseReleased(button: Int) {
        if (button == InputConstants.MOUSE_BUTTON_LEFT) dragging = false
    }

    private fun getSorting(): Collection<Feature> {
        if (screen.searchQuery == cq && ClickGui.panelSorting.value == lc) return sorted

        val suggestions = if (screen.searchQuery.isBlank()) features
        else SuggestionManager.getSuggestions(screen.searchQuery, features)

        cq = screen.searchQuery
        lc = ClickGui.panelSorting.value
        sorted = when (ClickGui.panelSorting.value) {
            0 -> suggestions.sortedBy { it.name }
            1 -> suggestions.sortedByDescending { it.name.width() }
            else -> suggestions
        }
        return sorted
    }

    fun openFeature(feature: Feature) {
        if (feature is IFeatureScreen) {
            if (feature.enabled) GuiUtils.setScreen(feature.createScreen())
            else NotificationManager.push("$MOD_NAME - ClickGui", "&fEnable &b${feature.name} &ffirst to open the settings!")
        }
        else if (feature.configSettings.isNotEmpty()) screen.openFeatureWindow(feature)
    }
}