package dev.riftwalk.utils

import dev.riftwalk.Riftwalk
import dev.riftwalk.event.EventBus
import dev.riftwalk.event.priority.EventPriority
import dev.riftwalk.event.impl.MainThreadPacketReceivedEvent
import dev.riftwalk.init.types.ISelfInit
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.*
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.PlayerTeam

object ScoreboardUtils: ISelfInit {
    private var cachedLines: List<Component> = emptyList()
    private var listDirty = true

    private val updatePackets = setOf(
        ClientboundSetScorePacket::class, ClientboundSetObjectivePacket::class,
        ClientboundSetDisplayObjectivePacket::class, ClientboundResetScorePacket::class,
        ClientboundSetPlayerTeamPacket::class
    )

    override fun init() {
        EventBus.register<MainThreadPacketReceivedEvent.Post>(EventPriority.HIGHEST) {
            if (updatePackets.none { it.java.isInstance(event.packet) }) return@register
            listDirty = true
        }
    }

    fun getLines(): List<Component> {
        if (listDirty) {
            cachedLines = fetchScoreboard()
            listDirty = false
        }
        return cachedLines
    }

    private fun fetchScoreboard(): List<Component> {
        val scoreboard = Riftwalk.mc.level?.scoreboard ?: return emptyList()
        val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return emptyList()

        val lines = mutableListOf(objective.displayName)
        scoreboard.listPlayerScores(objective).sortedByDescending { it.value }.take(15).forEach { score ->
            val name = score.ownerName().string
            lines.add(PlayerTeam.formatNameForTeam(scoreboard.getPlayersTeam(name), Component.literal(name)).copy())
        }

        return lines
    }
}