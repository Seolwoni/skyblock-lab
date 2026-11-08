package dev.riftwalk.features.impl.dev

import dev.riftwalk.Riftwalk
import dev.riftwalk.config.types.ButtonSetting
import dev.riftwalk.config.types.ToggleSetting
import dev.riftwalk.features.Feature
import dev.riftwalk.features.impl.dev.cosmetics.CosmeticData
import dev.riftwalk.features.impl.dev.cosmetics.badges.BadgeManager
import dev.riftwalk.features.impl.dev.cosmetics.badges.BadgeText
import dev.riftwalk.features.impl.dev.text.TextReplacer
import dev.riftwalk.ui.notification.NotificationManager
import dev.riftwalk.utils.*
import dev.riftwalk.utils.MathUtils.vec
import dev.riftwalk.utils.network.*
import com.mojang.authlib.GameProfile
import com.mojang.blaze3d.vertex.PoseStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.world.entity.Avatar
import java.util.*
import java.util.concurrent.*
import kotlin.math.abs
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

object Cosmetics: Feature(toggled = true) {
    @JvmStatic val customNames by ToggleSetting("Show Custom Names", true)
    @JvmStatic val showBadges by ToggleSetting("Show Badges", true)
    @JvmStatic val loreNames by ToggleSetting("Show Name in Lore", true).showIf { customNames.value }

    val customSizes by ToggleSetting("Show Custom Sizes", true)
    val showHalo by ToggleSetting("Show Halos", true)
    val showDragonWings by ToggleSetting("Show Dragon Wings", true)
    val reload by ButtonSetting("Reload Cosmetics") {
        if (System.currentTimeMillis() - lastReload >= 15_000) init()
        else NotificationManager.push("Cosmetics", "Please wait another ${NumbersUtils.formatTime(15_000 - (System.currentTimeMillis() - lastReload))} before reloading again.")
    }

    private lateinit var cosmeticPeople: Map<UUID, CosmeticData>
    private var lastReload = System.currentTimeMillis()

    override fun init() {
        scope.launch(Dispatchers.IO) {
            lastReload = System.currentTimeMillis()
            Riftwalk.logger.info("fetching cosmeticPeople")

            launch { BadgeManager.load() }
            WebUtils.getAs<Map<String, CosmeticData>>("${DataAPI.URL}/cosmeticPeople.json").onSuccess { data ->
                TextReplacer.ready = false
                cosmeticPeople = data.mapKeys { UUID.fromString(it.key) }
                BadgeText.init(cosmeticPeople)

                val uuids = cosmeticPeople.filter { it.value.hasCustomName }.map { it.key }
                val mojangData = ProfileUtils.getMojangBatched(uuids).getOrThrow()
                val customNames = ConcurrentHashMap<String, String>()

                for (mojang in mojangData) {
                    val uuid = Uuid.parseHex(mojang.uuid.remove("-")).toJavaUuid()
                    customNames[mojang.name] = cosmeticPeople[uuid] !!.name
                }

                TextReplacer.init(customNames)
                Riftwalk.logger.info("mojangData: ${mojangData.size}, uuids: ${uuids.size}")
            }.onFailure { cause ->
                Riftwalk.logger.error("Failed to load cosmetic people", cause)
                ChatUtils.modMessage("&cFailed to load cosmetic people: ${cause.message}")
            }.onSuccess {
                Riftwalk.logger.info("loaded all ${cosmeticPeople.size} cosmetic people")
            }
        }
    }

    @JvmStatic
    fun extractRenderStateHook(avatar: Avatar, state: AvatarRenderState) {
        if (! enabled) return
        if (avatar !is AbstractClientPlayer) return
        state.setData(GAME_PROFILE_KEY, avatar.gameProfile)
        state.setData(SNEAKING_KEY, avatar.isCrouching)
    }

    @JvmStatic
    fun scaleHook(state: AvatarRenderState, poseStack: PoseStack) {
        val gameProfile = state.getData(GAME_PROFILE_KEY) ?: return
        val data = cosmeticDataFor(gameProfile.id) ?: return

        if (customSizes.value && data.hasCustomSize) {
            if (data.sizeY < 0) poseStack.translate(0f, data.sizeY * 2f, 0f)
            poseStack.scale(data.sizeX, data.sizeY, data.sizeZ)
        }

        state.nameTagAttachment?.let { pos ->
            var scaleY = 1f
            var offset = 0f

            if (customSizes.value && data.hasCustomSize) scaleY = abs(data.sizeY)
            if (showHalo.value && data.hasHalo) offset += 0.15f

            state.nameTagAttachment = vec(pos.x, (pos.y + 0.15) * scaleY + offset, pos.z)
        }
    }

    fun cosmeticDataFor(uuid: UUID) = if (::cosmeticPeople.isInitialized) cosmeticPeople[uuid] else null
    val GAME_PROFILE_KEY = RenderStateDataKey.create<GameProfile> { "${Riftwalk.MOD_ID}:game_profile" }
    val SNEAKING_KEY = RenderStateDataKey.create<Boolean> { "${Riftwalk.MOD_ID}:sneaking" }
}