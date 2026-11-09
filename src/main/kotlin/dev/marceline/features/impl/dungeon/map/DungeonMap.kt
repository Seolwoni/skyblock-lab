package dev.marceline.features.impl.dungeon.map

import dev.marceline.config.types.*
import dev.marceline.event.impl.*
import dev.marceline.features.Feature
import dev.marceline.features.IFeatureWindow
import dev.marceline.utils.*
import dev.marceline.utils.dungeons.enums.SecretType
import dev.marceline.utils.dungeons.map.core.*
import dev.marceline.utils.dungeons.map.handlers.*
import dev.marceline.utils.location.LocationUtils
import dev.marceline.utils.render.world.Render3D.renderBlock
import net.minecraft.world.level.block.Blocks

object DungeonMap: Feature(), IFeatureWindow {
    override fun Feature.createWindow() = DungeonMapWindow(this)

    override fun init() {
        configSettings.addAll(MapConfig.configSettings)
        hudElements.add(MapRenderer)

        //#if CHEAT
        register<RenderWorldEvent> {
            if (! LocationUtils.inDungeon || LocationUtils.inBoss) return@register

            val mimicRoom = DungeonScanner.mimicRoom
            if (MapConfig.mimicEsp.value && ! ScoreTracker.mimicKilled && mimicRoom != null) {
                for (chestPos in mimicRoom.trappedChestPositions) {
                    if (! WorldUtils.getStateAt(chestPos).`is`(Blocks.TRAPPED_CHEST)) continue
                    if (mimicRoom.secretCoords[SecretType.CHEST]?.any { it == chestPos } != true) continue
                    event.ctx.renderBlock(chestPos, MapConfig.mimicEspColor.value, phase = true)
                }
            }
        }
        //#endif
    }
}