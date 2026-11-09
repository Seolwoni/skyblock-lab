package dev.marceline.features.impl.routes

//#if CHEAT
import dev.marceline.config.types.SliderSetting
import dev.marceline.config.types.ToggleSetting
import dev.marceline.event.impl.TickEvent
import dev.marceline.event.impl.WorldChangeEvent
import dev.marceline.features.Feature
import dev.marceline.features.impl.dungeon.solvers.puzzles.*
import dev.marceline.utils.dungeons.map.utils.ScanUtils
import dev.marceline.utils.location.LocationUtils
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
            dev.marceline.utils.Safely("AutoPuzzles tick") { tickPuzzles() }
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
            cooldown = dev.marceline.utils.Humanize.around(clickDelay.value) + dev.marceline.utils.Humanize.maybeBreak()
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
        // fresh offset per step; offset capped so the arrival stays inside the same block (offsetCap)
        if (AutoRoutes.arrivedAt(goal, p.position(), kotlin.math.sqrt(0.12), key = a.key to walkIndex) && abs(p.y - goal.y) < 1.2) {
            walkIndex++
            return
        }
        // careful steps: no sprint (with the human sprint delay), straight at the block centre
        AutoRoutes.sprintKey(false)
        val yaw = Math.toDegrees(kotlin.math.atan2(goal.z - p.z, goal.x - p.x)).toFloat() - 90f
        AutoRoutes.lastTurnTick = AutoRoutes.moveTick; AutoRoutes.lastDriveTick = AutoRoutes.moveTick
        dev.marceline.utils.Humanize.turnTo(p, yaw, p.xRot, 4f)
        mc.options.keyUp.isDown = true
        walkingNow = true
        mc.options.keyShift.isDown = a.key.startsWith("icefill") // sneak on ice so we don't slide past
    }
}
//#endif
