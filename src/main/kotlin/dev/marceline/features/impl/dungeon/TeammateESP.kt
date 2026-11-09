package dev.marceline.features.impl.dungeon

import dev.marceline.config.types.ToggleSetting
import dev.marceline.event.impl.CheckEntityGlowEvent
import dev.marceline.event.impl.RenderWorldEvent
import dev.marceline.features.Feature
import dev.marceline.utils.dungeons.DungeonListener
import dev.marceline.utils.location.LocationUtils
import dev.marceline.utils.render.RenderHelper.renderVec
import dev.marceline.utils.render.world.Render3D.renderString
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.world.entity.Entity

object TeammateESP: Feature(
    "Highlights your dungeon party.",
    //#if LEGIT
    //!name = "Teammate Highlight",
    //#endif
    jsonName = "Teammate ESP"
) {
    private val highlight by ToggleSetting("Highlight Teammates", true)
    private val drawName by ToggleSetting("Show Teammate Name", true)

    private val cache = HashMap<Int, Boolean>()

    override fun init() {
        register<CheckEntityGlowEvent> {
            if (! highlight.value) return@register
            if (! LocationUtils.inDungeon) return@register
            if (event.entity !is AbstractClientPlayer) return@register
            if (event.entity.uuid.version() != 4) return@register

            for (teammate in DungeonListener.dungeonTeammates) {
                if (teammate.entity?.id != event.entity.id) continue
                event.color = teammate.clazz.color
            }
        }

        register<RenderWorldEvent> {
            if (! drawName.value) return@register
            if (! LocationUtils.inDungeon) return@register
            for (teammate in DungeonListener.dungeonTeammatesNoSelf) {
                val entity = teammate.entity ?: continue
                val color = teammate.clazz.code
                val renderVec = entity.renderVec
                val distance = renderVec.distanceTo(player.renderVec)
                val scale = (distance * 0.12f).coerceAtLeast(1.0)

                event.ctx.renderString(
                    "&e[${teammate.clazz.name[0]}&e] $color${teammate.name}",
                    renderVec.x,
                    renderVec.y + entity.bbHeight + 0.7 + distance * 0.015f,
                    renderVec.z,
                    scale = scale,
                    phase = true
                )

                cache.clear()
            }
        }
    }

    @JvmStatic
    fun shouldHideNametag(entity: Entity): Boolean {
        return cache.getOrPut(entity.id) {
            if (! enabled) return@getOrPut false
            if (! drawName.value) return@getOrPut false
            if (! LocationUtils.inDungeon) return@getOrPut false
            DungeonListener.dungeonTeammatesNoSelf.any { it.entity?.id == entity.id }
        }
    }
}