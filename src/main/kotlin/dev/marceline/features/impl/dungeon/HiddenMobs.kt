package dev.marceline.features.impl.dungeon

//#if CHEAT

import dev.marceline.compat.Enderman
import dev.marceline.config.types.ToggleSetting
import dev.marceline.event.impl.MainThreadPacketReceivedEvent
import dev.marceline.features.Feature
import dev.marceline.init.DataDownloader
import dev.marceline.utils.location.LocationUtils
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.monster.Giant

object HiddenMobs: Feature("Reveals invisible mobs in dungeons.") {
    private val watcherMobs by lazy { DataDownloader.loadJson<List<String>>("watcherMobsNames.json") }

    private val showFels by ToggleSetting("Show Fels", true)
    private val showSa by ToggleSetting("Show Shadow Assassins", true)
    private val showStealthy by ToggleSetting("Show Stealthy", true)

    override fun init() {
        register<MainThreadPacketReceivedEvent.Post> {
            if (! showFels.value && ! showSa.value && ! showStealthy.value) return@register
            if (! LocationUtils.inDungeon) return@register
            val packet = event.packet as? ClientboundSetEntityDataPacket ?: return@register
            val entity = level.getEntity(packet.id)?.takeIf { it.isInvisible } ?: return@register
            val name = entity.displayName.string.trim()

            val isFel = entity is Enderman && showFels.value && name == "Dinnerbone"
            val isSA = entity is AbstractClientPlayer && showSa.value && name.contains("Shadow Assassin")
            val isWatcherMob = entity is AbstractClientPlayer && showStealthy.value && watcherMobs.any { name == it }
            val isGiant = entity is Giant && showStealthy.value && ! entity.getItemBySlot(EquipmentSlot.FEET).isEmpty

            if (isFel || isSA || isWatcherMob || isGiant) entity.isInvisible = false
        }
    }
}
//#endif