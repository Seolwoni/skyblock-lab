package dev.marceline.features.impl.dungeon.leapmenu

import dev.marceline.utils.ColorUtils.lerp
import dev.marceline.utils.MathUtils
import dev.marceline.utils.dungeons.DungeonListener
import dev.marceline.utils.dungeons.DungeonPlayer
import dev.marceline.utils.dungeons.F7Listener
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Color

abstract class LeapMenuRenderer {
    private val boxBg = Color(33, 33, 33)
    private val boxBgHover = Color(67, 67, 67)
    private val doorOpenerBg = Color(135, 135, 135)
    private val doorOpenerBgHover = Color.WHITE
    private val inCoreColor = Color(0, 120, 0)
    private val inCoreBox = MathUtils.aabb(68, 106, 54, 42, 155, 119)

    abstract fun render(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int)
    abstract fun getHoveredIndex(): Int?

    fun leapBoxColor(player: DungeonPlayer, isHovered: Boolean) = when {
        LeapMenu.showLastDoorOpener.value && DungeonListener.lastDoorOpener == player -> if (isHovered) doorOpenerBgHover else doorOpenerBg
        LeapMenu.tintDeadPlayers.value && player.isDead -> boxBg.lerp(Color.RED, 0.2f).let { if (isHovered) it.brighter() else it }
        LeapMenu.highlightInCore.value && F7Listener.section.ordinal > 4 && inCoreBox.contains(
            player.entity?.x ?: .0,
            player.entity?.y ?: .0,
            player.entity?.z ?: .0
        ) -> inCoreColor

        else -> if (isHovered) boxBgHover else boxBg
    }
}