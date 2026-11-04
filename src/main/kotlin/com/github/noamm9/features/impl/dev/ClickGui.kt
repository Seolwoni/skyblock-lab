package com.github.noamm9.features.impl.dev

import com.github.noamm9.config.types.ButtonSetting
import com.github.noamm9.config.types.ColorSetting
import com.github.noamm9.config.types.DropdownSetting
import com.github.noamm9.config.types.KeybindSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.KeyboardEvent
import com.github.noamm9.event.impl.MouseClickEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.ui.clickgui.ClickGuiScreen
import com.github.noamm9.ui.hud.HudEditorScreen
import com.github.noamm9.utils.GuiUtils
import com.mojang.blaze3d.platform.InputConstants
import java.awt.Color

object ClickGui: Feature("A feature used to change the ClickGui configuration.", toggled = true) {
    private val openKeybind by KeybindSetting("Open Keybind")
    val accentColor by ColorSetting("Accent Color", Color(139, 92, 246), false).withDescription("The accent color used by the whole ClickGui.")
    val guiStyle by DropdownSetting("GUI Style", 0, listOf("SkyHanni", "Classic")).withDescription("SkyHanni: one window with search, a category list and option cards with descriptions. Classic: the original NoammAddons panels.")
    val intro by DropdownSetting("Logo Intro", 1, listOf("Off", "First open", "Every open")).withDescription("Plays the Riftwalk logo animation when the menu opens (a click or a key skips it)")
    val glass by com.github.noamm9.config.types.SliderSetting("Glass", 40, 0, 100, 5, "%").withDescription("How see-through the menu is: the blurred world shows through like frosted glass. 0 = solid")
    val clickFeel by ToggleSetting("Click Feel", true).withDescription("A soft ripple where you click, cards and buttons dip for a moment, switch knobs stretch as they slide")
    val shaderCorners by ToggleSetting("Smooth Corners", true).withDescription("Draws rounded corners with a shader (anti-aliased, no steps). Turn off if menus look broken with your graphics setup")
    val customFont by ToggleSetting("Smooth Font", true).withDescription("Riftwalk menu uses the Pretendard font instead of Minecraft's pixel font")
    val riftClickSound by ToggleSetting("Riftwalk Click Sound", true)
        .withDescription("Menu clicks make a soft mechanical keyboard \"du-geok\" sound instead of Minecraft's button click")
    val hudPanels by ToggleSetting("HUD Panels", true).section("HUD Look")
        .withDescription("Draws a soft dark rounded panel with an accent strip behind every HUD element")
    val hudFont by ToggleSetting("Smooth HUD Font", true)
        .withDescription("HUD text uses the same smooth font as the menu instead of Minecraft's pixel font")
    val hudColors by ToggleSetting("Theme HUD Colours", true)
        .withDescription("Softens the bright Minecraft colours in HUD text into Void Rift tones (green stays green, red stays red)")
    val mobileMode by ToggleSetting("Mobile Mode", com.github.noamm9.utils.Platform.isMobile).section("Performance")
        .withDescription("For phone launchers (Pojav, Zalith, FCL...): lighter shadows and glows, fewer draw calls. Turned on by itself when an Android launcher is detected")
    val espStyle by DropdownSetting("ESP Style", 1, listOf("Classic", "Glow", "Corners")).section("World Look")
        .withDescription("How every ESP box is drawn. Classic: plain outline. Glow: a soft halo around a crisp outline and a lighter fill. Corners: the halo with clean corner brackets instead of full edges")
    val espGlow by com.github.noamm9.config.types.SliderSetting("ESP Glow", 35, 0, 100, 5, "%").withDescription("How strong the halo around ESP boxes is").showIf { espStyle.value != 0 }
    val labelBackdrop by ToggleSetting("Label Backdrop", true).withDescription("A soft dark plate behind text floating in the world (names, distances, waypoints) so it reads on any background")
    val themeColorsButton by ButtonSetting("Apply Theme Colours") {
        var n = 0
        com.github.noamm9.features.FeatureManager.features.forEach { f ->
            if (f === ClickGui) return@forEach
            f.configSettings.filterIsInstance<ColorSetting>().forEach { it.reset(); n ++ }
        }
        com.github.noamm9.ui.notification.NotificationManager.push("Riftwalk", "&fReset &d$n &fcolours to the Void Rift defaults")
    }.withDescription("Puts every ESP, waypoint and highlight colour back to its default, which now follows the Void Rift theme. Your own colour changes are overwritten.")

    private val masterKey by KeybindSetting("All Features On/Off Key").section("All Features")
        .withDescription("Turns every feature off, and the same key turns the ones that were on back on")
    val masterSaved by com.github.noamm9.config.types.TextInputSetting("All Off Saved", "").hidden()
    val serverMode by DropdownSetting("Server Mode", 0, listOf("Hypixel only", "Treat as Hypixel", "Always in dungeon")).section("Private Server").withDescription(
        "Hypixel only: dungeon features turn on only on Hypixel. Treat as Hypixel: any server counts, dungeons are detected from the scoreboard / tab list like on Hypixel. Always in dungeon: dungeon features stay on everywhere (for test worlds without a SkyBlock scoreboard)"
    )
    val forcedFloor by DropdownSetting("Floor", 13, listOf("F1", "F2", "F3", "F4", "F5", "F6", "F7", "M1", "M2", "M3", "M4", "M5", "M6", "M7")).showIf { serverMode.value == 2 }
        .withDescription("The floor the dungeon features assume in Always in dungeon mode")
    val panelSorting by DropdownSetting("Sorting", 1, listOf("A-Z Sorting", "Width Sorting", "No Sorting")).withDescription("The order of the features in the panels.")

    val editGuiButton by ButtonSetting("Open HUD Editor") {
        GuiUtils.setScreen(HudEditorScreen())
    }.withDescription("Opens the HUD Editor Screen where you can change you HUD elements size and position.")

    val resetButton by ButtonSetting("Reset Settings") {
        accentColor.reset()
    }.withDescription("Reverts settings back to their original values.")

    override fun init() {
        register<KeyboardEvent.KeyPressed> {
            if (mc.gui.screen() != null || event.action != InputConstants.PRESS) return@register
            if (masterKey.matches(event.keyEvent.key, mouse = false)) {
                com.github.noamm9.features.MasterSwitch.toggle()
                event.isCanceled = true
                return@register
            }
            if (! openKeybind.matches(event.keyEvent.key, mouse = false)) return@register

            GuiUtils.setScreen(createScreen())
            event.isCanceled = true
        }

        register<MouseClickEvent> {
            if (mc.gui.screen() != null || event.action != InputConstants.PRESS) return@register
            if (! openKeybind.matches(event.button, mouse = true)) return@register

            GuiUtils.setScreen(createScreen())
            event.isCanceled = true
        }
    }

    /** The settings screen in the chosen style. */
    @JvmStatic
    fun createScreen(): net.minecraft.client.gui.screens.Screen =
        if (guiStyle.value == 0) com.github.noamm9.ui.clickgui.SkyHanniScreen() else ClickGuiScreen()

    override fun toggle() = Unit
}