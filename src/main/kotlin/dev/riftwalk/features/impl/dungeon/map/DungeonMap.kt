package dev.riftwalk.features.impl.dungeon.map

import dev.riftwalk.config.types.*
import dev.riftwalk.event.impl.*
import dev.riftwalk.features.Feature
import dev.riftwalk.features.IFeatureWindow
import dev.riftwalk.utils.*
import dev.riftwalk.utils.dungeons.enums.SecretType
import dev.riftwalk.utils.dungeons.map.core.*
import dev.riftwalk.utils.dungeons.map.handlers.*
import dev.riftwalk.utils.location.LocationUtils
import dev.riftwalk.utils.render.world.Render3D.renderBlock
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