package dev.marceline.event.impl

import dev.marceline.event.Event
import dev.marceline.utils.dungeons.DungeonPlayer
import dev.marceline.utils.dungeons.enums.SecretType
import dev.marceline.utils.dungeons.enums.TerminalSection
import dev.marceline.utils.dungeons.map.core.*
import net.minecraft.core.BlockPos

abstract class DungeonEvent: Event(false) {
    abstract class RoomEvent(val room: UniqueRoom): DungeonEvent() {
        class onEnter(room: UniqueRoom): RoomEvent(room)
        class onExit(room: UniqueRoom): RoomEvent(room)
        class RotationFound(room: UniqueRoom, val rotation: Int, val corner: BlockPos): RoomEvent(room)

        class onStateChange(room: UniqueRoom, val oldState: RoomState, val newState: RoomState, val roomPlayers: List<DungeonPlayer>): RoomEvent(room)
    }

    class TileScannedEvent(val tile: Tile): DungeonEvent()

    class SecretEvent(val type: SecretType, val pos: BlockPos): DungeonEvent()

    class PlayerDeathEvent(val name: String, val reason: String): DungeonEvent()

    class Score(val oldScore: Int, val score: Int): DungeonEvent()

    object NecronDeathEvent: DungeonEvent()
    object BloodOpenEvent: DungeonEvent()
    object BossEnterEvent: DungeonEvent()
    object RunStartedEvent: DungeonEvent()
    object RunEndedEvent: DungeonEvent()

    object MimicKilled: DungeonEvent()
    object PrinceKilled: DungeonEvent()
    object BatKilled: DungeonEvent()

    abstract class TerminalStageEvent(val section: TerminalSection): DungeonEvent() {
        class Start(section: TerminalSection): TerminalStageEvent(section)
        class Task(section: TerminalSection, val task: TerminalSection.Task, val player: DungeonPlayer?): TerminalStageEvent(section)
        class GateDestroyed(section: TerminalSection): TerminalStageEvent(section)
        class End(section: TerminalSection): TerminalStageEvent(section)
    }
}