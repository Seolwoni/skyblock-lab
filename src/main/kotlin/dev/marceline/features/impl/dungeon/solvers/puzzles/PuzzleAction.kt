package dev.marceline.features.impl.dungeon.solvers.puzzles

import net.minecraft.core.BlockPos

/** What a solver wants done next (read by Auto Puzzles). [key] identifies the action so it is done only once. */
sealed class PuzzleAction(val key: String) {
    class Click(val pos: BlockPos, key: String): PuzzleAction(key)
    /** Walk over these blocks in order (stand on top of each). */
    class Walk(val blocks: List<BlockPos>, key: String): PuzzleAction(key)
}
