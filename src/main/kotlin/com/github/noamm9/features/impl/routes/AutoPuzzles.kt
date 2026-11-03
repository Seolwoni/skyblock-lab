package com.github.noamm9.features.impl.routes

//#if CHEAT
import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.features.impl.dungeon.solvers.puzzles.*
import com.github.noamm9.utils.dungeons.map.utils.ScanUtils
import com.github.noamm9.utils.location.LocationUtils
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import kotlin.math.abs

/**
 * Does what the puzzle solvers show: clicks the right chest / button / lever, walks the right pad or path.
 * Needs the matching solver turned on in Puzzle Solvers. Blaze, Creeper Beams and Ice Path need a bow or
 * dodging and are not done here.
 */
object AutoPuzzles: Feature("Solves puzzles by itself using the Puzzle Solvers' answers: Three Weirdos, Tic Tac Toe, Quiz, Boulder, Water Board, Teleport Maze and Ice Fill.") {
    private val weirdos by ToggleSetting("Three Weirdos", true).section("Puzzles")
    private val ttt by ToggleSetting("Tic Tac Toe", true)
    private val quiz by ToggleSetting("Quiz", true)
    private val boulder by ToggleSetting("Boulder", true)
    private val water by ToggleSetting("Water Board", true)
    private val tpMaze by ToggleSetting("Teleport Maze", true)
    private val iceFill by ToggleSetting("Ice Fill", false).withDescription("Experimental: walks the ice path block by block without sprinting")
    private val clickDelay by SliderSetting("Click Delay", 8, 2, 30, 1, " ticks").section("Timing").withDescription("Ticks to wait after each click before the next one")

    private val done = HashSet<String>()
    private var cooldown = 0
    private var walkIndex = 0
    private var walkKey: String? = null
    var busy = false
        private set

    /** Rooms this can finish (Auto Dungeon goes into these instead of skipping them). */
    fun supports(room: String) = enabled && when (room) {
        "Three Weirdos" -> weirdos.value
        "Tic Tac Toe" -> ttt.value
        "Quiz" -> quiz.value
        "Boulder" -> boulder.value
        "Water Board" -> water.value
        "Teleport Maze" -> tpMaze.value
        "Ice Fill" -> iceFill.value
        else -> false
    }

    override fun init() {
        register<WorldChangeEvent> { reset() }
        register<TickEvent.Start> {
            busy = false
            val wasWalking = walkingNow
            walkingNow = false
            runCatching { tickPuzzles() }
            if (wasWalking && ! walkingNow) AutoRoutes.releaseAll()
        }
    }

    private var walkingNow = false

    private fun tickPuzzles() {
        run {
            if (! LocationUtils.inDungeon || LocationUtils.inBoss || mc.gui.screen() != null) return@run
            if (AutoRoutes.isRunning) return@run
            if (cooldown > 0) { cooldown--; return@run }
            val room = ScanUtils.currentRoom?.name ?: return@run
            if (! supports(room)) return@run
            val action = actionFor(room)?.takeIf { it.key !in done } ?: return@run
            busy = true
            when (action) {
                is PuzzleAction.Click -> click(action)
                is PuzzleAction.Walk -> walk(action)
            }
        }
    }

    private fun reset() {
        done.clear()
        cooldown = 0
        walkIndex = 0
        walkKey = null
    }

    private fun actionFor(room: String): PuzzleAction? = when (room) {
        "Three Weirdos" -> ThreeWeirdosSolver.autoAction()
        "Tic Tac Toe" -> TicTacToeSolver.autoAction()
        "Quiz" -> QuizSolver.autoAction()
        "Boulder" -> BoulderSolver.autoAction()
        "Water Board" -> WaterBoardSolver.autoAction()
        "Teleport Maze" -> TeleportMazeSolver.autoAction()
        "Ice Fill" -> IceFillSolver.autoAction()
        else -> null
    }

    private fun click(a: PuzzleAction.Click) {
        val p = mc.player ?: return
        val centre = Vec3.atCenterOf(a.pos)
        if (p.eyePosition.distanceTo(centre) > 4.2) {
            AutoRoutes.travelTick(centre, 2.5)
            return
        }
        if (AutoRoutes.lookAndUse(centre)) {
            done += a.key
            cooldown = com.github.noamm9.utils.Humanize.around(clickDelay.value) + com.github.noamm9.utils.Humanize.maybeBreak()
        }
    }

    private fun walk(a: PuzzleAction.Walk) {
        if (a.key != walkKey) {
            walkKey = a.key
            walkIndex = 0
        }
        val p = mc.player ?: return
        val b: BlockPos = a.blocks.getOrNull(walkIndex) ?: run {
            done += a.key
            AutoRoutes.releaseAll()
            return
        }
        val goal = Vec3(b.x + 0.5, b.y + 1.0, b.z + 0.5)
        val dx = p.x - goal.x
        val dz = p.z - goal.z
        if (dx * dx + dz * dz < 0.12 && abs(p.y - goal.y) < 1.2) {
            walkIndex++
            return
        }
        // careful steps: no sprint, straight at the block centre
        mc.options.keySprint.isDown = false
        val yaw = Math.toDegrees(kotlin.math.atan2(goal.z - p.z, goal.x - p.x)).toFloat() - 90f
        com.github.noamm9.utils.Humanize.turnTo(p, yaw, p.xRot, 4f)
        mc.options.keyUp.isDown = true
        walkingNow = true
        mc.options.keyShift.isDown = a.key.startsWith("icefill") // sneak on ice so we don't slide past
    }
}
//#endif
