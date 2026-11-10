package dev.marceline.features.impl.routes

//#if CHEAT
import dev.marceline.config.types.DropdownSetting
import dev.marceline.config.types.KeybindSetting
import dev.marceline.config.types.SliderSetting
import dev.marceline.config.types.ToggleSetting
import dev.marceline.event.impl.RenderWorldEvent
import dev.marceline.event.impl.TickEvent
import dev.marceline.utils.render.Render2D.drawString
import dev.marceline.utils.render.world.Render3D.renderBlock
import dev.marceline.utils.render.world.Render3D.renderLine
import java.awt.Color
import dev.marceline.event.impl.WorldChangeEvent
import dev.marceline.features.Feature
import dev.marceline.utils.ChatUtils.modMessage
import dev.marceline.utils.MathUtils
import dev.marceline.utils.PlayerUtils
import dev.marceline.utils.items.EtherwarpHelper
import dev.marceline.utils.items.ItemUtils.skyblockId
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ambient.Bat
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import xyz.yourboykyle.secretroutes.Main
import xyz.yourboykyle.secretroutes.dungeons.detection.DungeonScanner
import xyz.yourboykyle.secretroutes.utils.RoomDirectionUtils
import xyz.yourboykyle.secretroutes.utils.RoomRotationUtils
import xyz.yourboykyle.secretroutes.utils.RotationUtils
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Follows the Secret Routes route of the current room by itself: walks the path, etherwarps,
 * throws pearls, breaks stonk blocks, places superbooms, flips levers and grabs the secret.
 * Secret Routes moves on to the next step when the secret is collected, and this picks up the new step.
 */
object AutoRoutes: Feature("Follows the current room's secret route automatically (walk, etherwarp, pearl, stonk, superboom, levers, secret).") {
    internal val toggleKey by KeybindSetting("Start / Stop Key").withDescription("Press to start following the route in your room, press again to stop")
    internal val speed by DropdownSetting("Speed", 0, listOf("0-Tick", "Fastest", "Fast", "Careful")).withDescription(
        "0-Tick: sends the new rotation to the server itself and acts in the same tick (etherwarps, pearls, levers and hits happen instantly). Fastest: acts on the next tick. Fast: one extra tick between actions. Careful: a few ticks between actions"
    )
    internal val etherTimeout by SliderSetting("Etherwarp Timeout", 12, 4, 40, 1, " ticks").withDescription("Give up on an etherwarp that did not land after this long and try again")
    internal val sprint by ToggleSetting("Sprint", true)
    internal val autoJump by ToggleSetting("Auto Jump", true).withDescription("Jumps when the next point is higher or something is in the way")
    internal val doMines by ToggleSetting("Break Stonk Blocks", true).section("Actions").withDescription("Breaks the route's mine blocks with a Dungeonbreaker or pickaxe from the hotbar")
    internal val doTnt by ToggleSetting("Place Superbooms", true).withDescription("Uses Superboom TNT on the route's crypt/wall spots")
    internal val doPearls by ToggleSetting("Throw Pearls", true).withDescription("Throws ender pearls at the recorded angle")
    internal val doLevers by ToggleSetting("Flip Levers", true)
    internal val climbAotv by ToggleSetting("Climb With AOTV", true).withDescription(
        "When the next point is above you (a shaft, a ledge, after falling in a hole): etherwarps onto it if the landing checks out, otherwise teleports up with the AOTV"
    )
    internal val fastTravel by ToggleSetting("Fast Travel", true).withDescription(
        "Instead of walking to a point more than 6 blocks away: etherwarps onto it when the landing checks out, otherwise teleports with the AOTV when it's far enough (9.5+ blocks)"
    )
    internal val doSecret by ToggleSetting("Collect Secret", true).withDescription("Opens chests/levers/skulls and hits bats at the end of each step")
    internal val chatInfo by ToggleSetting("Chat Messages", true)
    internal val clearMode by DropdownSetting("Clear", 1, listOf("Off", "Star mobs + minibosses", "Whole room")).section("Combat").withDescription(
        "Whole room: kills every mob in the room you are in before doing its secrets. Star mobs: only stops for starred mobs and minibosses near the route"
    )
    internal val useHype by ToggleSetting("Use Hyperion", true).withDescription("Wither Impact (Hyperion / Astraea / Scylla / Valkyrie): teleports into the pack and casts looking down. Falls back to hitting if none is in the hotbar")
    internal val hypeTeleport by ToggleSetting("Teleport Into Mobs", true).withDescription("Casts towards a pack up to 10 blocks away to get there instantly, instead of walking")
    internal val manaCost by SliderSetting("Mana Per Cast", 300, 0, 1000, 10).hideIf { clearMode.value == 0 }.withDescription("Wither Impact's mana cost for you. Below it, the Hyperion is swung as a sword instead")
    internal val castDelay by SliderSetting("Cast Delay", 3, 1, 20, 1, " ticks").withDescription("Ticks between Wither Impact casts (watch your mana)")
    internal val blastRadius by SliderSetting("Blast Radius", 6.0, 3.0, 10.0, 0.5, " blocks").withDescription("Wither Impact damage radius, used to decide when a pack is close enough")
    internal val killStars by ToggleSetting("Kill Star Mobs", true).hideIf { clearMode.value == 0 }.withDescription("Stops to kill starred (✯) mobs near the route")
    internal val killMinis by ToggleSetting("Kill Minibosses", true).hideIf { clearMode.value == 0 }.withDescription("Shadow Assassin, Lost / Frozen Adventurer, Angry Archaeologist, King Midas, Diamond Guy")
    internal val attackRange by SliderSetting("Attack Range", 3.0, 2.0, 3.0, 0.1, " blocks").withDescription("Normal hand reach at most")
    internal val detourRange by SliderSetting("Detour Range", 6.0, 0.0, 12.0, 0.5, " blocks").withDescription("How far it leaves the route to chase a target (0 = only hit what is already in reach)")
    internal val fightBack by ToggleSetting("Fight Back", true).withDescription("When something hits you, kills the closest mob within 3 blocks first (any mob, not just starred)")
    internal val hypePriority by ToggleSetting("Prioritise Star Mobs", true).hideIf { clearMode.value == 0 }.withDescription(
        "When picking where to cast, a starred mob or miniboss counts as 3 normal mobs"
    )
    internal val hypeLead by ToggleSetting("Lead Moving Mobs", true).hideIf { clearMode.value == 0 }.withDescription(
        "Aims the teleport at where the pack will be a few ticks from now, not where it is"
    )
    internal val hitDelay by SliderSetting("Hit Delay", 2, 1, 10, 1, " ticks").withDescription("Ticks between hits")
    internal val weaponSlot by SliderSetting("Weapon Slot", 0, 0, 9, 1).withDescription("Hotbar slot (1-9) to hold while fighting. 0 = keep the current item")
    internal val recover by ToggleSetting("Recover When Stuck", true).section("Recovery").withDescription(
        "Instead of stopping when stuck: jumps, teleports up/forward with the AOTV, then re-finds the closest point of the route. Stops only after several failed tries"
    )
    internal val etherRetries by SliderSetting("Etherwarp Retries", 3, 1, 8, 1).withDescription("Failed etherwarps to the same block before walking / AOTV-ing there instead")
    internal val skipStuckSecret by ToggleSetting("Skip Unreachable Secret", true).withDescription("If a secret can't be collected in 10 seconds, move on instead of stopping (Auto Dungeon goes to the next room)")
    internal val useAssistPath by ToggleSetting("Use Assist Path", true).section("Assist").withDescription(
        "With Assist Core + Assist Path installed: when the straight walk is blocked, or the target is far, finds a way around with Assist Path (no breaking or placing)"
    )
    internal val smoothTurning by ToggleSetting("Smooth Turning", true).withDescription(
        "With Assist Core installed: turns with Assist's ease-in/ease-out head movement instead of snapping. Actions wait until the aim has arrived"
    )
    internal val aimPause by SliderSetting("Aim Pause", 2, 0, 10, 1, " ticks").withDescription(
        "A short pause after the aim arrives, before clicking (varies by one tick each time). 0 = click right away"
    )
    internal val assistLog by ToggleSetting("Assist Journal", true).withDescription("Writes runs (room, why it stopped) to the Assist usage log")
    internal val showPlan by ToggleSetting("Show Plan", true).section("Debug").withDescription("Draws the next point, etherwarp and action in the world, even before you start - check the route is read right")
    internal val statusHud by ToggleSetting("Status HUD", true).withDescription("Room, step, phase and next action on screen (move it in the HUD editor)")
    internal val checkEther by ToggleSetting("Check Etherwarp Landing", true).withDescription("Before warping, tries a few aim points on the block and picks one whose predicted landing is the route's block")

    internal const val REACH = 4.4

    internal enum class Phase { MOVE, ETHER_AIM, ETHER_WAIT, ACTION_AIM, MINING, PEARL_WAIT, AOTV_WAIT, SECRET, DONE }

    internal class Step(
        val path: List<BlockPos>,
        val ethers: MutableSet<BlockPos>,
        val mines: MutableList<BlockPos>,
        val tnts: MutableList<BlockPos>,
        val levers: MutableList<BlockPos>,
        val pearls: MutableList<Pair<Vec3, MathUtils.Rotation>>,
        val secretType: String?,
        val secretPos: BlockPos?,
    )

    internal var running = false
    internal var stepRef: JsonObject? = null
    internal var step: Step? = null
    internal var pathIndex = 0
    internal var phase = Phase.MOVE
    internal var wait = 0
    internal var timer = 0
    internal var actionTarget: BlockPos? = null
    internal var actionUse = true
    internal var pendingPearl: Pair<Vec3, MathUtils.Rotation>? = null
    internal var etherTarget: BlockPos? = null
    internal var stuckTicks = 0
    internal var secretTimer = 0
    internal var status = "idle"
    internal var hitCooldown = 0
    internal var pendingCast = false
    internal var retaliating = false
    internal var assistFailedGoal: BlockPos? = null
    internal var settleKey: Pair<Float, Float>? = null
    internal var settleLeft = 0
    internal var fightTarget: Entity? = null
    internal val AOTV_IDS = setOf("ASPECT_OF_THE_VOID", "ASPECT_OF_THE_END")
    internal var climbTries = 0
    internal var aotvFromY = 0.0
    internal var aotvFrom: Vec3 = Vec3.ZERO
    internal val WITHER_BLADES = setOf("HYPERION", "ASTRAEA", "SCYLLA", "VALKYRIE")
    internal val MINIBOSSES = setOf("Shadow Assassin", "Lost Adventurer", "Frozen Adventurer", "Angry Archaeologist", "King Midas", "Diamond Guy")
    internal var lastEtherCheck: Boolean? = null
    internal var lastPos: Vec3? = null
    internal var recoveries = 0
    internal val etherFails = HashMap<BlockPos, Int>()
    internal val ignored = HashMap<Int, Int>() // entity id -> ticks left
    internal var castsOnTarget = 0
    internal var retaliateTicks = 0
    internal var stallId = - 1
    internal var stallHp = 0f
    internal var stallPos: Vec3? = null
    internal var stallTicks = 0
    internal var targetHealth = - 1f
    internal var targetId = - 1

    // human movement (Human Behaviour › Movement); maths in HumanMotion
    internal val moveRnd = java.util.Random()
    internal var moveTick = 0L          // counted at the end of each tick
    internal var lastTurnTick = - 1L    // a turn() this tick: the idle drift leaves the view alone
    internal var lastDriveTick = - 100L // any of the route features moved / turned recently
    internal var walking = false
    internal var startYawOff = 0f
    internal var startWobbleLeft = 0
    internal var walkPitchOff = 0f
    internal var idleYaw = 0f
    internal var idlePitch = 0f
    internal var jumpWait = - 1
    internal var sprintNow = false
    internal var sprintWant = false
    internal var sprintWait = 0
    internal var repathIn = 0
    internal var stuckJumpAt = 12
    internal var assistAfter = 8
    internal var arrivalKey: Any? = null
    internal var arrivalOff: DoubleArray? = null

    /** Set by Auto Dungeon in Blood: blood mobs are players too, so anything that isn't a teammate or the Watcher counts. */
    @JvmField var campMode = false

    /** Set by Auto Dungeon: clear the whole room whatever Clear is set to. */
    @JvmField var forceClear = false
    /** Why the last run stopped (read by Auto Dungeon). */
    var lastStopReason: String? = null
        private set
    val isRunning get() = running
    internal val clearValue get() = if (forceClear) 2 else clearMode.value

    override fun init() {
        register<TickEvent.Start> {
            if (toggleKey.isPressed() && mc.gui.screen() == null) {
                if (running) stop("stopped") else start()
            }
            if (running) runCatching(::tick).onFailure { dev.marceline.utils.Safely.report("AutoRoutes tick", it); stop("error: ${it.message}") }
        }
        register<WorldChangeEvent> { if (running) stop("world changed") }
        register<TickEvent.End> { runCatching { idleLook() }; moveTick++ }

        register<RenderWorldEvent> {
            if (! showPlan.value) return@register
            val s = currentStepForPreview() ?: return@register
            val p = mc.player ?: return@register
            val next = s.path.getOrNull(pathIndex)
            if (next != null) {
                event.ctx.renderBlock(next.below(), Color(0, 220, 255), fill = false, phase = true)
                event.ctx.renderLine(p.position().add(0.0, 0.1, 0.0), Vec3(next.x + 0.5, next.y + 0.1, next.z + 0.5), Color(0, 220, 255), 2, phase = true)
            }
            s.ethers.forEach { event.ctx.renderBlock(it, Color(170, 0, 255, 90), fill = true, outline = true, phase = true) }
            etherTarget?.takeIf { running && phase in setOf(Phase.ETHER_AIM, Phase.ETHER_WAIT) }?.let {
                event.ctx.renderBlock(it, Color(255, 0, 255), fill = false, phase = true, lineWidth = 4)
            }
            (s.mines + s.tnts + s.levers).forEach { event.ctx.renderBlock(it, Color(255, 200, 0), fill = false, phase = true) }
            fightTarget?.takeIf { running && it.isAlive }?.let {
                event.ctx.renderLine(p.eyePosition, it.boundingBox.center, Color(255, 60, 60), 2, phase = true)
            }
            s.secretPos?.let { event.ctx.renderBlock(it, Color(0, 255, 80), fill = false, phase = true, lineWidth = 3) }
        }

        hudElement("Auto Routes Status", { statusHud.value && ! AssistBridge.hudHidden() }) { ctx, example ->
            val s = if (example) null else currentStepForPreview()
            val lines = if (example) listOf("§bAuto Routes §aRUNNING", "§7Room: §fBalcony-4  §7step §f1", "§7Path §f2/3  §7phase §fETHER_WAIT", "§7etherwarp landing: §aok")
            else listOf(
                "§bAuto Routes " + if (running) "§aRUNNING" else "§7idle",
                "§7Room: §f${Main.currentRoom?.name ?: "-"}  §7step §f${(Main.currentRoom?.currentSecretIndex ?: -1) + 1}",
                "§7Path §f${minOf(pathIndex, s?.path?.size ?: 0)}/${s?.path?.size ?: 0}  §7phase §f$phase",
                "§7" + status + (lastEtherCheck?.let { if (it) "  §aether ok" else "  §cether miss" } ?: ""),
            )
            val w = lines.maxOf { with(dev.marceline.utils.render.RenderHelper) { it.width() } }
            val h = lines.size * 10
            // the shared Marceline card behind the text
            dev.marceline.ui.hud.HudCard.draw(ctx, 0f, 0f, w + 12f, h + 6f, 4f, glassAlpha = 215)
            lines.forEachIndexed { i, l -> ctx.drawString(l, 7, 4 + i * 10) }
            return@hudElement (w + 12) to (h + 6)
        }.also { it.themedPanel = false }
    }

    /** The step being run, or (when idle) the step that would run, so the plan can be checked before starting. */
    internal fun currentStepForPreview(): Step? {
        if (running) return step
        if (! RouteBridge.routeActive()) return null
        val room = Main.currentRoom ?: return null
        val raw = room.currentSecretWaypoints ?: return null
        if (raw !== stepRef) {
            stepRef = raw
            step = runCatching { parse(raw, room.name) }.getOrNull()
            pathIndex = 0
        }
        return step
    }

    override fun onDisable() {
        super.onDisable()
        if (running) stop(null)
    }

    /** Starts following the room (used by the key and by Auto Dungeon). Returns false if there is nothing to do here. */
    fun startRun(quiet: Boolean = false): Boolean {
        if (! RouteBridge.routeActive() && ! (clearValue == 2 && DungeonScanner.currentRoom != null)) return false
        if (running) return true
        running = true
        stepRef = null
        phase = Phase.MOVE
        recoveries = 0
        etherFails.clear()
        lastStopReason = null
        if (! quiet) info("&aFollowing route. Press the key again to stop.")
        if (assistLog.value) AssistBridge.logStart("Auto Routes in ${Main.currentRoom?.name ?: DungeonScanner.currentRoom?.getName() ?: "?"}")
        return true
    }

    /** Stops from outside (Auto Dungeon). */
    fun stopRun(reason: String?) {
        if (running) stop(reason)
    }

    internal fun start() {
        if (! RouteBridge.routeActive() && ! (clearValue == 2 && DungeonScanner.currentRoom != null)) return info("&cNo route or room here.")
        running = true
        stepRef = null
        phase = Phase.MOVE
        info("&aFollowing route. Press the key again to stop.")
        if (assistLog.value) AssistBridge.logStart("Auto Routes in ${Main.currentRoom?.name ?: DungeonScanner.currentRoom?.getName() ?: "?"}")
    }

    /** View priorities (AimBroker): following a route is travel; the idle drift while standing is idle. */
    internal val AIM_TRAVEL = dev.marceline.utils.AimPriority.TRAVEL
    internal val AIM_IDLE = dev.marceline.utils.AimPriority.IDLE

    internal fun stop(reason: String?) {
        dev.marceline.utils.Aim.release(this)
        lastStopReason = reason
        if (running && assistLog.value) AssistBridge.logEnd(reason ?: "stopped", "room ${Main.currentRoom?.name ?: "-"}")
        running = false
        AssistBridge.reset()
        releaseKeys()
        stepRef = null
        step = null
        if (reason != null) info("&eAuto Routes $reason.")
    }

    internal fun info(msg: String) {
        if (chatInfo.value) modMessage(msg)
    }

    internal val zeroTick get() = speed.value == 0
    internal val actionDelay get() = when (speed.value) { 0, 1 -> 0; 2 -> 1; else -> 3 } + if (speed.value != 0 && dev.marceline.features.impl.general.HumanBehaviour.enabled) dev.marceline.utils.Humanize.delay(1, 4) else 0

    // ------------------------------------------------------------------ main loop

    internal fun tick() {
        val p = mc.player ?: return stop("no player")
        if (mc.gui.screen() != null) return releaseKeys()
        if (hitCooldown > 0) hitCooldown--
        if (ignored.isNotEmpty()) ignored.entries.removeIf { e -> e.setValue(e.value - 1); e.value <= 0 }
        val busyFlying = phase == Phase.ETHER_AIM || phase == Phase.ETHER_WAIT || phase == Phase.PEARL_WAIT || phase == Phase.MINING
        if (! busyFlying && wait <= 0 && fight()) return
        if (! RouteBridge.routeActive()) return stop(if (clearValue == 2) "room cleared, no route left" else "finished (no more steps)")

        val room = Main.currentRoom ?: return
        val raw = room.currentSecretWaypoints ?: return
        if (raw !== stepRef) {
            stepRef = raw
            step = parse(raw, room.name)
            pathIndex = 0
            phase = Phase.MOVE
            wait = 0
            stuckTicks = 0
            secretTimer = 0
            climbTries = 0
        }
        val s = step ?: return
        if (wait > 0) {
            wait--
            return
        }

        when (phase) {
            Phase.MOVE -> move(s, p.position())
            Phase.ETHER_AIM -> {
                PlayerUtils.rightClick()
                phase = Phase.ETHER_WAIT
                timer = 0
            }

            Phase.ETHER_WAIT -> {
                val t = etherTarget ?: return run { phase = Phase.MOVE }
                timer++
                if (p.position().distanceTo(standOn(t)) < 1.2) {
                    PlayerUtils.toggleSneak(false)
                    pathIndex++
                    phase = Phase.MOVE
                    wait = actionDelay
                }
                else if (timer > etherTimeout.value) {
                    val n = (etherFails[t] ?: 0) + 1
                    etherFails[t] = n
                    if (n >= etherRetries.value) {
                        s.ethers.remove(t) // walk / AOTV there instead
                        PlayerUtils.toggleSneak(false)
                        if (assistLog.value) AssistBridge.logEvent("etherwarp to ${t.x}, ${t.y}, ${t.z} failed $n times, walking instead")
                    }
                    phase = Phase.MOVE // aim again
                }
            }

            Phase.ACTION_AIM -> {
                if (actionUse) PlayerUtils.rightClick() else PlayerUtils.leftClick()
                phase = Phase.MOVE
                wait = actionDelay
            }

            Phase.MINING -> {
                val t = actionTarget
                if (t == null || p.level().getBlockState(t).isAir) {
                    mc.options.keyAttack.isDown = false
                    phase = Phase.MOVE
                    wait = actionDelay
                }
                else {
                    mc.options.keyAttack.isDown = turnTo(Vec3.atCenterOf(t)) || mc.options.keyAttack.isDown
                    if (++timer > 60) {
                        mc.options.keyAttack.isDown = false
                        s.mines.remove(t)
                        phase = Phase.MOVE
                    }
                }
            }

            Phase.AOTV_WAIT -> {
                val p0 = aotvFromY
                timer++
                if (abs(p.y - p0) > 1.0 || p.position().distanceTo(aotvFrom) > 3.0 || timer > 12) {
                    phase = Phase.MOVE
                    wait = actionDelay
                }
            }

            Phase.PEARL_WAIT -> {
                PlayerUtils.rightClick()
                timer = 0
                phase = Phase.MOVE
                wait = 10 + actionDelay // let the pearl land
            }

            Phase.SECRET -> collectSecret(s, p.position())
            Phase.DONE -> {}
        }
    }

    /** One tick of fighting in place (Blood camp): true while it has something to hit. */
    fun campTick(): Boolean {
        if (hitCooldown > 0) hitCooldown--
        if (ignored.isNotEmpty()) ignored.entries.removeIf { e -> e.setValue(e.value - 1); e.value <= 0 }
        val p = mc.player ?: return false
        val target = findRoomTarget(p) ?: return false.also { fightTarget = null }
        fightTarget = target
        val slot = if (useHype.value) PlayerUtils.findHotbarSlot { it.skyblockId in WITHER_BLADES } else null
        if (slot != null && dev.marceline.utils.ManaGuard.canCast(manaCost.value)) {
            hypeFight(p, target, slot)
            return true
        }
        if (slot != null && weaponSlot.value !in 1 .. 9) PlayerUtils.swapToSlot(slot)
        val dist = p.eyePosition.distanceTo(target.boundingBox.clipNearest(p.eyePosition))
        if (dist > attackRange.value) {
            walkTowards(p.position(), target.position())
            return true
        }
        releaseMovement()
        if (weaponSlot.value in 1 .. 9) PlayerUtils.swapToSlot(weaponSlot.value - 1)
        if (! turnTo(target.boundingBox.center, 4f) || hitCooldown > 0) return true
        hitCooldown = dev.marceline.utils.Humanize.around(hitDelay.value)
        sendRotation()
        mc.gameMode?.attack(p, target)
        p.swing(InteractionHand.MAIN_HAND)
        return true
    }

    internal fun AABB.clipNearest(from: Vec3) = Vec3(from.x.coerceIn(minX, maxX), from.y.coerceIn(minY, maxY), from.z.coerceIn(minZ, maxZ))

    // ------------------------------------------------------------------ travel API (Auto Dungeon)

    internal var travelWait = 0
    internal var travelStuck = false

    enum class Travel { MOVING, ARRIVED, STUCK }

    /**
     * One tick of getting to [goal] while no route is running: fights back, AOTVs on long straight stretches,
     * otherwise walks (with Assist Path when installed). Reports STUCK once walking made no progress for 2 seconds.
     */
    fun travelTick(goal: Vec3, tolerance: Double = 1.5, hopWait: (() -> Int)? = null, allowHop: Boolean = true): Travel {
        val p = mc.player ?: return Travel.STUCK
        val pos = p.position()
        if (arrivedAt(goal, pos, tolerance, key = goal) && abs(pos.y - goal.y) < 3.0) {
            releaseMovement()
            AssistBridge.reset()
            arrivalKey = null // the next trip draws a new offset, even to the same spot
            return Travel.ARRIVED
        }
        if (hitCooldown > 0) hitCooldown--
        if (travelWait > 0) {
            travelWait--
            return Travel.MOVING
        }
        if (fight()) return Travel.MOVING
        val flat = horizontal(pos, goal)
        if (allowHop && fastTravel.value && p.onGround() && flat > 10.0 && abs(goal.y - pos.y) < 6.0 && aotvLeap(pos, goal)) {
            if (phase == Phase.AOTV_WAIT) {
                phase = Phase.MOVE
                travelWait = hopWait?.invoke() ?: 6
            }
            return Travel.MOVING
        }
        travelStuck = false
        walkTowards(pos, goal)
        if (travelStuck) {
            travelStuck = false
            // one AOTV hop to get unstuck before giving up
            return if (aotvLeap(pos, goal.add(0.0, 1.0, 0.0))) Travel.MOVING.also { phase = Phase.MOVE; travelWait = hopWait?.invoke() ?: 6 } else Travel.STUCK
        }
        return Travel.MOVING
    }

    /** Turns to [target] and right-clicks it once the aim has arrived. True when it clicked. */
    fun lookAndUse(target: Vec3): Boolean {
        releaseMovement()
        if (! turnTo(target)) return false
        sendRotation()
        PlayerUtils.rightClick()
        return true
    }

    fun releaseAll() {
        releaseKeys()
        AssistBridge.reset()
    }

    internal fun releaseMovement() {
        mc.options.keyUp.isDown = false
        mc.options.keyJump.isDown = false
        mc.options.keySprint.isDown = false
        walking = false
        jumpWait = - 1
        sprintNow = false; sprintWant = false; sprintWait = 0
    }

    internal fun releaseKeys() {
        releaseMovement()
        mc.options.keyAttack.isDown = false
        PlayerUtils.toggleSneak(false)
    }

    @Suppress("unused")
    internal fun ItemStack.isEtherItem() = EtherwarpHelper.getEtherwarpDistance(this) != null
}
//#endif
