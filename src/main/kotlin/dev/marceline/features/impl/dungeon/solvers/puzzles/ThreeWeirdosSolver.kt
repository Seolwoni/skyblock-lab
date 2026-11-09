package dev.marceline.features.impl.dungeon.solvers.puzzles

import dev.marceline.Marceline.mc
import dev.marceline.event.impl.ChatMessageEvent
import dev.marceline.event.impl.DungeonEvent
import dev.marceline.event.impl.PlayerInteractEvent
import dev.marceline.features.impl.dungeon.solvers.PuzzleSolvers
import dev.marceline.features.impl.dungeon.solvers.PuzzleSolvers.colorCorrect
import dev.marceline.features.impl.dungeon.solvers.PuzzleSolvers.colorWrong
import dev.marceline.features.impl.dungeon.solvers.PuzzleSolvers.removeChests
import dev.marceline.utils.ChatUtils.unformattedText
import dev.marceline.utils.WorldUtils
import dev.marceline.utils.dungeons.map.core.RoomState
import dev.marceline.utils.dungeons.map.utils.ScanUtils
import dev.marceline.utils.dungeons.map.utils.ScanUtils.rotate
import dev.marceline.utils.location.LocationUtils
import dev.marceline.utils.render.world.Render3D.renderBlock
import dev.marceline.utils.render.world.RenderContext
import net.minecraft.core.BlockPos
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.level.block.Blocks
import java.util.concurrent.*
import kotlin.math.floor

object ThreeWeirdosSolver: PuzzleSolver {
    override val enabled get() = PuzzleSolvers.weirdos.value

    private val npcRegex = Regex("\\[NPC] (\\w+): (.+)")
    private var wrongPositions = CopyOnWriteArraySet<BlockPos>()
    private var correctPos: BlockPos? = null

    private val inThreeWeirdos get() = wrongPositions.isNotEmpty() || correctPos != null

    override fun onChat(event: ChatMessageEvent) {
        if (! LocationUtils.inDungeon) return
        val currentRoom = ScanUtils.currentRoom.takeIf { it?.name == "Three Weirdos" } ?: return
        val (npcName, text) = npcRegex.find(event.unformattedText)?.destructured ?: return

        val isSolution = solutions.any { it.matches(text) }
        val isWrong = wrong.any { it.matches(text) }

        if (! isSolution && ! isWrong) return

        val correctNPC = mc.level?.entitiesForRendering()?.filterIsInstance<ArmorStand>()
            ?.find { it.name.unformattedText.contains(npcName) } ?: return

        val npcBlockPos = BlockPos(floor(correctNPC.x).toInt(), 69, floor(correctNPC.z).toInt())
        val chestPos = npcBlockPos.offset(BlockPos(1, 0, 0).rotate(360 - currentRoom.rotation !!))

        if (isSolution) {
            mc.player?.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 2f, 1f)
            correctPos = chestPos
        }
        else {
            if (removeChests.value) WorldUtils.setBlockAt(chestPos, Blocks.AIR.defaultBlockState())
            wrongPositions.add(chestPos)
        }
    }

    internal fun autoAction(): PuzzleAction? = correctPos?.let { PuzzleAction.Click(it, "weirdos-${it.toShortString()}") }

    override fun onRenderWorld(ctx: RenderContext) {
        if (! inThreeWeirdos) return

        correctPos?.let { pos ->
            ctx.renderBlock(pos, colorCorrect.value)
        }

        if (! removeChests.value) wrongPositions.forEach { pos ->
            ctx.renderBlock(pos, colorWrong.value)
        }
    }

    override fun onInteract(event: PlayerInteractEvent.RIGHT_CLICK.BLOCK) {
        if (! inThreeWeirdos) return
        if (LocationUtils.inBoss) return
        if (event.pos == correctPos) reset()
    }

    override fun onStateChange(event: DungeonEvent.RoomEvent.onStateChange) {
        if (! inThreeWeirdos) return
        if (event.room.name != "Three Weirdos") return
        if (event.newState != RoomState.DISCOVERED) return
        reset()
    }

    override fun reset() {
        if (removeChests.value) {
            for (pos in wrongPositions) {
                WorldUtils.setBlockAt(pos, Blocks.CHEST.defaultBlockState())
            }
        }

        correctPos = null
        wrongPositions.clear()
    }

    private val solutions = listOf(
        Regex("The reward is not in my chest!"),
        Regex("At least one of them is lying, and the reward is not in .+'s chest.?"),
        Regex("My chest doesn't have the reward. We are all telling the truth.?"),
        Regex("My chest has the reward and I'm telling the truth!"),
        Regex("The reward isn't in any of our chests.?"),
        Regex("Both of them are telling the truth. Also, .+ has the reward in their chest.?"),
    )

    private val wrong = listOf(
        Regex("One of us is telling the truth!"),
        Regex("They are both telling the truth. The reward isn't in .+'s chest."),
        Regex("We are all telling the truth!"),
        Regex(".+ is telling the truth and the reward is in his chest."),
        Regex("My chest doesn't have the reward. At least one of the others is telling the truth!"),
        Regex("One of the others is lying."),
        Regex("They are both telling the truth, the reward is in .+'s chest."),
        Regex("They are both lying, the reward is in my chest!"),
        Regex("The reward is in my chest."),
        Regex("The reward is not in my chest. They are both lying."),
        Regex(".+ is telling the truth."),
        Regex("My chest has the reward.")
    )
}