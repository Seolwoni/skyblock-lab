package com.github.noamm9.features.impl.routes

//#if CHEAT
import com.github.noamm9.config.types.DropdownSetting
import com.github.noamm9.config.types.KeybindSetting
import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.RenderWorldEvent
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.utils.render.Render2D.drawString
import com.github.noamm9.utils.render.world.Render3D.renderBlock
import com.github.noamm9.utils.render.world.Render3D.renderLine
import java.awt.Color
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.utils.ChatUtils.modMessage
import com.github.noamm9.utils.MathUtils
import com.github.noamm9.utils.PlayerUtils
import com.github.noamm9.utils.items.EtherwarpHelper
import com.github.noamm9.utils.items.ItemUtils.skyblockId
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
    private val toggleKey by KeybindSetting("Start / Stop Key").withDescription("Press to start following the route in your room, press again to stop")
    private val speed by DropdownSetting("Speed", 0, listOf("0-Tick", "Fastest", "Fast", "Careful")).withDescription(
        "0-Tick: sends the new rotation to the server itself and acts in the same tick (etherwarps, pearls, levers and hits happen instantly). Fastest: acts on the next tick. Fast: one extra tick between actions. Careful: a few ticks between actions"
    )
    private val etherTimeout by SliderSetting("Etherwarp Timeout", 12, 4, 40, 1, " ticks").withDescription("Give up on an etherwarp that did not land after this long and try again")
    private val sprint by ToggleSetting("Sprint", true)
    private val autoJump by ToggleSetting("Auto Jump", true).withDescription("Jumps when the next point is higher or something is in the way")
    private val doMines by ToggleSetting("Break Stonk Blocks", true).section("Actions").withDescription("Breaks the route's mine blocks with a Dungeonbreaker or pickaxe from the hotbar")
    private val doTnt by ToggleSetting("Place Superbooms", true).withDescription("Uses Superboom TNT on the route's crypt/wall spots")
    private val doPearls by ToggleSetting("Throw Pearls", true).withDescription("Throws ender pearls at the recorded angle")
    private val doLevers by ToggleSetting("Flip Levers", true)
    private val climbAotv by ToggleSetting("Climb With AOTV", true).withDescription(
        "When the next point is above you (a shaft, a ledge, after falling in a hole): etherwarps onto it if the landing checks out, otherwise teleports up with the AOTV"
    )
    private val doSecret by ToggleSetting("Collect Secret", true).withDescription("Opens chests/levers/skulls and hits bats at the end of each step")
    private val chatInfo by ToggleSetting("Chat Messages", true)
    private val clearMode by DropdownSetting("Clear", 1, listOf("Off", "Star mobs + minibosses", "Whole room")).section("Combat").withDescription(
        "Whole room: kills every mob in the room you are in before doing its secrets. Star mobs: only stops for starred mobs and minibosses near the route"
    )
    private val useHype by ToggleSetting("Use Hyperion", true).withDescription("Wither Impact (Hyperion / Astraea / Scylla / Valkyrie): teleports into the pack and casts looking down. Falls back to hitting if none is in the hotbar")
    private val hypeTeleport by ToggleSetting("Teleport Into Mobs", true).withDescription("Casts towards a pack up to 10 blocks away to get there instantly, instead of walking")
    private val castDelay by SliderSetting("Cast Delay", 3, 1, 20, 1, " ticks").withDescription("Ticks between Wither Impact casts (watch your mana)")
    private val blastRadius by SliderSetting("Blast Radius", 6.0, 3.0, 10.0, 0.5, " blocks").withDescription("Wither Impact damage radius, used to decide when a pack is close enough")
    private val killStars by ToggleSetting("Kill Star Mobs", true).hideIf { clearMode.value == 0 }.withDescription("Stops to kill starred (✯) mobs near the route")
    private val killMinis by ToggleSetting("Kill Minibosses", true).hideIf { clearMode.value == 0 }.withDescription("Shadow Assassin, Lost / Frozen Adventurer, Angry Archaeologist, King Midas, Diamond Guy")
    private val attackRange by SliderSetting("Attack Range", 3.0, 2.0, 3.0, 0.1, " blocks").withDescription("Normal hand reach at most")
    private val detourRange by SliderSetting("Detour Range", 6.0, 0.0, 12.0, 0.5, " blocks").withDescription("How far it leaves the route to chase a target (0 = only hit what is already in reach)")
    private val hitDelay by SliderSetting("Hit Delay", 2, 1, 10, 1, " ticks").withDescription("Ticks between hits")
    private val weaponSlot by SliderSetting("Weapon Slot", 0, 0, 9, 1).withDescription("Hotbar slot (1-9) to hold while fighting. 0 = keep the current item")
    private val useAssistPath by ToggleSetting("Use Assist Path", true).section("Assist").withDescription(
        "With Assist Core + Assist Path installed: when the straight walk is blocked, or the target is far, finds a way around with Assist Path (no breaking or placing)"
    )
    private val smoothTurning by ToggleSetting("Smooth Turning", true).withDescription(
        "With Assist Core installed: turns with Assist's ease-in/ease-out head movement instead of snapping. Actions wait until the aim has arrived"
    )
    private val aimPause by SliderSetting("Aim Pause", 2, 0, 10, 1, " ticks").withDescription(
        "A short pause after the aim arrives, before clicking (varies by one tick each time). 0 = click right away"
    )
    private val assistLog by ToggleSetting("Assist Journal", true).withDescription("Writes runs (room, why it stopped) to the Assist usage log")
    private val showPlan by ToggleSetting("Show Plan", true).section("Debug").withDescription("Draws the next point, etherwarp and action in the world, even before you start - check the route is read right")
    private val statusHud by ToggleSetting("Status HUD", true).withDescription("Room, step, phase and next action on screen (move it in the HUD editor)")
    private val checkEther by ToggleSetting("Check Etherwarp Landing", true).withDescription("Before warping, tries a few aim points on the block and picks one whose predicted landing is the route's block")

    private const val REACH = 4.4

    private enum class Phase { MOVE, ETHER_AIM, ETHER_WAIT, ACTION_AIM, MINING, PEARL_WAIT, AOTV_WAIT, SECRET, DONE }

    private class Step(
        val path: List<BlockPos>,
        val ethers: MutableSet<BlockPos>,
        val mines: MutableList<BlockPos>,
        val tnts: MutableList<BlockPos>,
        val levers: MutableList<BlockPos>,
        val pearls: MutableList<Pair<Vec3, MathUtils.Rotation>>,
        val secretType: String?,
        val secretPos: BlockPos?,
    )

    private var running = false
    private var stepRef: JsonObject? = null
    private var step: Step? = null
    private var pathIndex = 0
    private var phase = Phase.MOVE
    private var wait = 0
    private var timer = 0
    private var actionTarget: BlockPos? = null
    private var actionUse = true
    private var pendingPearl: Pair<Vec3, MathUtils.Rotation>? = null
    private var etherTarget: BlockPos? = null
    private var stuckTicks = 0
    private var secretTimer = 0
    private var status = "idle"
    private var hitCooldown = 0
    private var pendingCast = false
    private var assistFailedGoal: BlockPos? = null
    private var settleKey: Pair<Int, Int>? = null
    private var settleLeft = 0
    private var fightTarget: Entity? = null
    private val AOTV_IDS = setOf("ASPECT_OF_THE_VOID", "ASPECT_OF_THE_END")
    private var climbTries = 0
    private var aotvFromY = 0.0
    private val WITHER_BLADES = setOf("HYPERION", "ASTRAEA", "SCYLLA", "VALKYRIE")
    private val MINIBOSSES = setOf("Shadow Assassin", "Lost Adventurer", "Frozen Adventurer", "Angry Archaeologist", "King Midas", "Diamond Guy")
    private var lastEtherCheck: Boolean? = null
    private var lastPos: Vec3? = null

    override fun init() {
        register<TickEvent.Start> {
            if (toggleKey.isPressed() && mc.gui.screen() == null) {
                if (running) stop("stopped") else start()
            }
            if (running) runCatching(::tick).onFailure { stop("error: ${it.message}") }
        }
        register<WorldChangeEvent> { if (running) stop("world changed") }

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
            lines.forEachIndexed { i, l -> ctx.drawString(l, 0, i * 10) }
            return@hudElement lines.maxOf { mc.font.width(it.replace(Regex("§."), "")) } to lines.size * 10
        }
    }

    /** The step being run, or (when idle) the step that would run, so the plan can be checked before starting. */
    private fun currentStepForPreview(): Step? {
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

    private fun start() {
        if (! RouteBridge.routeActive() && ! (clearMode.value == 2 && DungeonScanner.currentRoom != null)) return info("&cNo route or room here.")
        running = true
        stepRef = null
        phase = Phase.MOVE
        info("&aFollowing route. Press the key again to stop.")
        if (assistLog.value) AssistBridge.logStart("Auto Routes in ${Main.currentRoom?.name ?: DungeonScanner.currentRoom?.getName() ?: "?"}")
    }

    private fun stop(reason: String?) {
        if (running && assistLog.value) AssistBridge.logEnd(reason ?: "stopped", "room ${Main.currentRoom?.name ?: "-"}")
        running = false
        AssistBridge.reset()
        releaseKeys()
        stepRef = null
        step = null
        if (reason != null) info("&eAuto Routes $reason.")
    }

    private fun info(msg: String) {
        if (chatInfo.value) modMessage(msg)
    }

    private val zeroTick get() = speed.value == 0
    private val actionDelay get() = when (speed.value) { 0, 1 -> 0; 2 -> 1; else -> 3 }

    // ------------------------------------------------------------------ main loop

    private fun tick() {
        val p = mc.player ?: return stop("no player")
        if (mc.gui.screen() != null) return releaseKeys()
        if (hitCooldown > 0) hitCooldown--
        val busyFlying = phase == Phase.ETHER_AIM || phase == Phase.ETHER_WAIT || phase == Phase.PEARL_WAIT || phase == Phase.MINING
        if (! busyFlying && wait <= 0 && fight()) return
        if (! RouteBridge.routeActive()) return stop(if (clearMode.value == 2) "room cleared, no route left" else "finished (no more steps)")

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
                if (p.y > p0 + 1.0 || timer > 12) {
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

    private fun move(s: Step, pos: Vec3) {
        val eye = mc.player!!.eyePosition

        // Things to do from where we are, before walking on.
        if (doMines.value) s.mines.firstOrNull { eye.distanceTo(Vec3.atCenterOf(it)) <= REACH }?.let { m ->
            if (mc.player!!.level().getBlockState(m).isAir) {
                s.mines.remove(m); return
            }
            val slot = PlayerUtils.findHotbarSlot { it.skyblockId == "DUNGEONBREAKER" || it.skyblockId.contains("PICKAXE") || it.skyblockId.contains("DRILL") }
            if (slot != null) PlayerUtils.swapToSlot(slot)
            releaseMovement()
            actionTarget = m
            timer = 0
            phase = Phase.MINING
            return
        }
        if (doTnt.value) s.tnts.firstOrNull { eye.distanceTo(Vec3.atCenterOf(it)) <= REACH }?.let { t ->
            val slot = PlayerUtils.findHotbarSlot { it.skyblockId.contains("SUPERBOOM") } ?: return run { s.tnts.remove(t) }
            PlayerUtils.swapToSlot(slot)
            if (aimThen(Vec3.atCenterOf(t), use = true)) s.tnts.remove(t)
            return
        }
        if (doLevers.value) s.levers.firstOrNull { eye.distanceTo(Vec3.atCenterOf(it)) <= REACH }?.let { l ->
            if (aimThen(Vec3.atCenterOf(l), use = true)) s.levers.remove(l)
            return
        }
        if (doPearls.value) s.pearls.firstOrNull { horizontal(pos, it.first) < 0.8 && abs(pos.y - it.first.y) < 1.5 }?.let { pearl ->
            val slot = PlayerUtils.findHotbarSlot { it.skyblockId == "ENDER_PEARL" || it.hoverName.string.contains("Ender Pearl") } ?: return run { s.pearls.remove(pearl) }
            PlayerUtils.swapToSlot(slot)
            releaseMovement()
            if (! turn(pearl.second.yaw, pearl.second.pitch, 1f)) return
            s.pearls.remove(pearl)
            if (zeroTick) {
                useNow()
                wait = 10
                return
            }
            phase = Phase.PEARL_WAIT
            wait = actionDelay
            return
        }

        if (pathIndex >= s.path.size) {
            releaseMovement()
            phase = Phase.SECRET
            return
        }

        val target = s.path[pathIndex]
        val ether = if (target in s.ethers) target else target.below().takeIf { it in s.ethers }
        if (ether != null && pos.distanceTo(standOn(ether)) > 1.2) {
            val slot = PlayerUtils.findHotbarSlot { EtherwarpHelper.getEtherwarpDistance(it) != null }
            if (slot != null) {
                PlayerUtils.swapToSlot(slot)
                releaseMovement()
                val alreadySneaking = mc.player!!.isShiftKeyDown
                PlayerUtils.toggleSneak(true)
                val aim = etherAim(ether, mc.player!!.inventory.getItem(slot))
                if (! turn(aim.yaw, aim.pitch, 1f)) return
                etherTarget = ether
                status = "etherwarp to ${ether.x}, ${ether.y}, ${ether.z}"
                if (zeroTick && alreadySneaking) {
                    useNow()
                    phase = Phase.ETHER_WAIT
                    timer = 0
                }
                else {
                    phase = Phase.ETHER_AIM
                    wait = actionDelay
                }
                return
            }
        }

        val goal = if (ether != null) standOn(ether) else Vec3(target.x + 0.5, target.y.toDouble(), target.z + 0.5)
        if (horizontal(pos, goal) < 0.55 && abs(pos.y - goal.y) < 1.6) {
            pathIndex++
            stuckTicks = 0
            climbTries = 0
            return
        }
        if (climbAotv.value && ether == null && goal.y - pos.y > 1.3 && (horizontal(pos, goal) < 4.0 || stuckTicks > 10) && mc.player!!.onGround()) {
            if (climb(s, pos, goal)) return
        }
        walkTowards(pos, goal)
    }

    private fun walkTowards(pos: Vec3, goal: Vec3) {
        val p = mc.player!!
        val goalBlock = BlockPos.containing(goal.x, goal.y, goal.z)
        if (useAssistPath.value && AssistBridge.pathLoaded && goalBlock != assistFailedGoal && (AssistBridge.walking || stuckTicks > 8 || horizontal(pos, goal) > 14.0)) {
            when (AssistBridge.walkTo(mc, p, goalBlock)) {
                AssistBridge.RUN -> {
                    stuckTicks = 0
                    lastPos = pos
                    status = "assist path to ${goalBlock.x}, ${goalBlock.y}, ${goalBlock.z}"
                    return
                }
                AssistBridge.DONE -> {
                    stuckTicks = 0
                    releaseMovement()
                    return
                }
                else -> {
                    if (assistLog.value) AssistBridge.logEvent("Assist Path gave up: ${AssistBridge.failReason() ?: "no way"}")
                    assistFailedGoal = goalBlock // don't plan to this point again, finish with the straight walk
                    stuckTicks = 0
                }
            }
        }
        val yaw = Math.toDegrees(atan2(goal.z - pos.z, goal.x - pos.x)).toFloat() - 90f
        turn(yaw, if (speed.value == 3) p.xRot else 10f, 6f, settle = false)
        mc.options.keyUp.isDown = true
        mc.options.keySprint.isDown = sprint.value
        val needJump = autoJump.value && p.onGround() && ((goal.y - pos.y > 0.6 && horizontal(pos, goal) < 2.5) || p.horizontalCollision)
        mc.options.keyJump.isDown = needJump

        val moved = lastPos?.distanceTo(pos) ?: 1.0
        lastPos = pos
        stuckTicks = if (moved < 0.02) stuckTicks + 1 else 0
        if (stuckTicks in 12 .. 30 && p.onGround()) mc.options.keyJump.isDown = true
        if (stuckTicks > 40) stop("stuck - can't reach ${goal.x.toInt()}, ${goal.y.toInt()}, ${goal.z.toInt()}")
        status = "walk to ${goal.x.toInt()}, ${goal.y.toInt()}, ${goal.z.toInt()}"
    }

    private fun collectSecret(s: Step, pos: Vec3) {
        val timer = ++secretTimer
        val sp = s.secretPos
        if (! doSecret.value || sp == null || s.secretType == null || s.secretType == "exitroute") {
            if (timer > 40) phase = Phase.DONE
            return
        }
        when (s.secretType) {
            "interact" -> if (timer % 10 == 1) {
                if (mc.player!!.eyePosition.distanceTo(Vec3.atCenterOf(sp)) > REACH) walkTowards(pos, Vec3.atCenterOf(sp))
                else aimThen(Vec3.atCenterOf(sp), use = true)
            }

            "item" -> if (horizontal(pos, Vec3.atCenterOf(sp)) > 0.4) walkTowards(pos, Vec3(sp.x + 0.5, sp.y.toDouble(), sp.z + 0.5)) else releaseMovement()
            "bat" -> {
                releaseMovement()
                val bat = mc.player!!.level().getEntitiesOfClass(Bat::class.java, mc.player!!.boundingBox.inflate(8.0)).minByOrNull { it.distanceTo(mc.player!!) }
                if (bat != null && timer % 3 == 0) {
                    if (! turnTo(bat.boundingBox.center)) return
                    if (zeroTick) {
                        sendRotation()
                        mc.gameMode?.attack(mc.player!!, bat)
                        mc.player!!.swing(InteractionHand.MAIN_HAND)
                    }
                    else {
                        actionUse = false
                        phase = Phase.ACTION_AIM
                    }
                }
            }
        }
        if (timer > 200) stop("couldn't get the secret")
    }

    // ------------------------------------------------------------------ combat

    /** Kills the nearest star mob / miniboss in detour range. Returns true while busy fighting. */
    private fun fight(): Boolean {
        if (clearMode.value == 0) return false
        val p = mc.player ?: return false
        val target = if (clearMode.value == 2) findRoomTarget(p) else findTarget(p)
        fightTarget = target
        if (target == null) return false

        val hypeSlot = if (useHype.value) PlayerUtils.findHotbarSlot { it.skyblockId in WITHER_BLADES } else null
        if (hypeSlot != null) return hypeFight(p, target, hypeSlot)

        val dist = p.eyePosition.distanceTo(target.boundingBox.clipNearest(p.eyePosition))
        if (dist > attackRange.value) {
            walkTowards(p.position(), target.position())
            status = "chasing ${target.name.string}"
            return true
        }

        releaseMovement()
        if (weaponSlot.value in 1 .. 9) PlayerUtils.swapToSlot(weaponSlot.value - 1)
        status = "fighting ${target.name.string}"
        if (! turnTo(target.boundingBox.center, 4f)) return true
        if (hitCooldown > 0) return true
        hitCooldown = hitDelay.value
        if (zeroTick) {
            sendRotation()
            mc.gameMode?.attack(p, target)
            p.swing(InteractionHand.MAIN_HAND)
        }
        else PlayerUtils.leftClick() // rotation from last tick is already on the server
        return true
    }

    /** Wither Impact: teleport into the pack (if far), then cast looking straight down so the blast covers us. */
    private fun hypeFight(p: Player, target: Entity, slot: Int): Boolean {
        PlayerUtils.swapToSlot(slot)
        val dist = p.position().distanceTo(target.position())
        val inBlast = dist <= blastRadius.value - 1.0
        if (! inBlast && ! (hypeTeleport.value && dist <= 11.0)) {
            walkTowards(p.position(), target.position())
            status = "walking to ${target.name.string}"
            return true
        }
        releaseMovement()
        if (pendingCast) { // rotation went out last tick, now cast
            PlayerUtils.rightClick()
            pendingCast = false
            return true
        }
        if (hitCooldown > 0) return true
        hitCooldown = castDelay.value
        val aimed = if (inBlast) {
            status = "casting on ${target.name.string}"
            turn(p.yRot, 90f, 3f)
        }
        else {
            status = "teleporting to ${target.name.string}"
            turnTo(target.position().add(0.0, 0.2, 0.0), 2f)
        }
        if (! aimed) {
            hitCooldown = 0 // cast as soon as the aim arrives
            return true
        }
        if (zeroTick) useNow() else pendingCast = true
        return true
    }

    /** The mob to go for when clearing the whole room: the one with the most other mobs around it, then the closest. */
    private fun findRoomTarget(p: Player): Entity? {
        val room = DungeonScanner.currentRoom ?: return findTarget(p)
        val centres = room.roomComponents.map { it.vec2() }.toSet()
        val area = p.boundingBox.inflate(48.0, 20.0, 48.0)
        val mobs = p.level().getEntitiesOfClass(LivingEntity::class.java, area) { e ->
            e.isAlive && e.health > 0f && e !is ArmorStand && e !is Bat && e != p &&
                e.javaClass.simpleName != "WitherBoss" &&
                (e !is Player || e.gameProfile.name in MINIBOSSES || e.name.string in MINIBOSSES) &&
                DungeonScanner.getRoomCentre(e.blockX, e.blockZ) in centres
        }
        if (mobs.isEmpty()) return null
        val r2 = blastRadius.value * blastRadius.value
        return mobs.maxWithOrNull(compareBy<LivingEntity> { m -> mobs.count { it.distanceToSqr(m) <= r2 } }.thenBy { - p.distanceToSqr(it) })
    }

    private fun AABB.clipNearest(from: Vec3) = Vec3(from.x.coerceIn(minX, maxX), from.y.coerceIn(minY, maxY), from.z.coerceIn(minZ, maxZ))

    private fun findTarget(p: Player): Entity? {
        val range = maxOf(detourRange.value, attackRange.value)
        val level = p.level()
        val area = p.boundingBox.inflate(range + 2.0)
        val found = LinkedHashSet<Entity>()

        if (killStars.value) for (stand in level.getEntitiesOfClass(ArmorStand::class.java, area)) {
            val name = stand.customName?.string ?: continue
            if (! name.contains("✯")) continue
            val offset = if (name.uppercase().contains("WITHERMANCER")) 3 else 1
            val byId = level.getEntity(stand.id - offset)
            val mob = byId?.takeIf { it !is ArmorStand && it is LivingEntity }
                ?: level.getEntities(stand, stand.boundingBox.move(0.0, - 1.5, 0.0).inflate(0.6, 1.0, 0.6)) { it is LivingEntity && it !is ArmorStand && it != p }.minByOrNull { it.distanceToSqr(stand) }
            if (mob != null) found += mob
        }
        if (killMinis.value) for (other in level.getEntitiesOfClass(Player::class.java, area)) {
            if (other == p || other.isInvisible) continue
            if (other.gameProfile.name in MINIBOSSES || other.name.string in MINIBOSSES) found += other
        }

        return found.filter { it.isAlive && (it as? LivingEntity)?.health?.let { h -> h > 0f } != false && p.distanceTo(it) <= range + 1.0 }
            .minByOrNull { p.distanceToSqr(it) }
    }

    /** Rotate now, click on a later tick (the server needs the new rotation first). */
    /** Aims at [target], then uses / hits it. Returns false while still turning (call again next tick). */
    private fun aimThen(target: Vec3, use: Boolean): Boolean {
        releaseMovement()
        if (! turnTo(target)) return false
        if (zeroTick) {
            sendRotation()
            if (use) PlayerUtils.rightClick() else PlayerUtils.leftClick()
            return true
        }
        actionUse = use
        phase = Phase.ACTION_AIM
        wait = actionDelay
        return true
    }

    /** Snaps, or with Smooth Turning turns like Assist Core does. True once looking within [tolerance] degrees. */
    private fun turn(yaw: Float, pitch: Float, tolerance: Float = 2f, settle: Boolean = true): Boolean {
        val p = mc.player ?: return false
        val arrived = if (smoothTurning.value && AssistBridge.coreLoaded) AssistBridge.turn(p, yaw, pitch, tolerance)
        else {
            PlayerUtils.rotate(yaw, pitch)
            true
        }
        if (! arrived) {
            settleKey = null
            return false
        }
        if (! settle || aimPause.value <= 0) return true

        // Same aim (within ~3 degrees) waits once; a new aim waits again.
        val key = Math.round(yaw / 3f) to Math.round(pitch / 3f)
        if (key != settleKey) {
            settleKey = key
            settleLeft = (aimPause.value + kotlin.random.Random.nextInt(- 1, 2)).coerceAtLeast(0)
        }
        if (settleLeft > 0) {
            settleLeft--
            return false
        }
        return true
    }

    private fun turnTo(target: Vec3, tolerance: Float = 2f, settle: Boolean = true): Boolean {
        val rot = MathUtils.calcYawPitch(target)
        return turn(rot.yaw, rot.pitch, tolerance, settle)
    }

    /** 0-Tick: tell the server the new look direction right away, so a click in this same tick uses it. */
    private fun sendRotation() {
        val p = mc.player ?: return
        mc.connection?.send(ServerboundMovePlayerPacket.Rot(p.yRot, p.xRot, p.onGround(), p.horizontalCollision))
    }

    /** 0-Tick right click with the held item (etherwarp, pearl): rotation packet first, then the use packet. */
    private fun useNow() {
        val p = mc.player ?: return
        sendRotation()
        mc.gameMode?.useItem(p, InteractionHand.MAIN_HAND)
        p.swing(InteractionHand.MAIN_HAND)
    }

    /**
     * Gets up to [goal] when walking can't: etherwarp onto the block under it if the landing is predicted,
     * otherwise an AOTV teleport (no sneak) towards it. Returns true if it started something this tick.
     */
    private fun climb(s: Step, pos: Vec3, goal: Vec3): Boolean {
        val p = mc.player ?: return false
        if (++climbTries > 4 * 20) { // about 4 tries (each try is turning + waiting)
            stop("can't get up to ${goal.x.toInt()}, ${goal.y.toInt()}, ${goal.z.toInt()}")
            return true
        }
        val under = BlockPos.containing(goal.x, goal.y - 0.5, goal.z)
        val etherSlot = PlayerUtils.findHotbarSlot { EtherwarpHelper.getEtherwarpDistance(it) != null }
        if (etherSlot != null && under !in s.ethers && ! p.level().getBlockState(under).isAir) {
            etherAim(under, p.inventory.getItem(etherSlot))
            if (lastEtherCheck == true) {
                s.ethers += under // the normal etherwarp step takes it from here
                status = "etherwarp up to ${under.x}, ${under.y}, ${under.z}"
                return true
            }
        }
        val aotvSlot = PlayerUtils.findHotbarSlot { it.skyblockId in AOTV_IDS } ?: return false
        PlayerUtils.swapToSlot(aotvSlot)
        releaseMovement()
        PlayerUtils.toggleSneak(false)
        if (mc.player!!.isShiftKeyDown) return true // let the un-sneak reach the server first
        val dx = goal.x - pos.x
        val dz = goal.z - pos.z
        val yaw = Math.toDegrees(atan2(dz, dx)).toFloat() - 90f
        val flat = sqrt(dx * dx + dz * dz)
        val pitch = - Math.toDegrees(atan2(goal.y + 0.2 - (pos.y + p.eyeHeight), flat)).toFloat()
        status = "AOTV up to ${goal.x.toInt()}, ${goal.y.toInt()}, ${goal.z.toInt()}"
        if (! turn(yaw, pitch.coerceIn(- 90f, 90f), 2f)) return true
        aotvFromY = pos.y
        if (zeroTick) useNow() else PlayerUtils.rightClick()
        phase = Phase.AOTV_WAIT
        timer = 0
        return true
    }

    /**
     * Picks where to look so the etherwarp lands on [target]: tries the top face centre and a few points around it,
     * using Noamm's etherwarp prediction (same raycast rules as the server), sneaking eye height.
     */
    private fun etherAim(target: BlockPos, stack: ItemStack): MathUtils.Rotation {
        val centre = Vec3(target.x + 0.5, target.y + 0.98, target.z + 0.5)
        val fallback = MathUtils.calcYawPitch(centre)
        if (! checkEther.value) return fallback.also { lastEtherCheck = null }
        val p = mc.player ?: return fallback
        val dist = EtherwarpHelper.getEtherwarpDistance(stack) ?: 57.0
        val offsets = listOf(0.0, 0.25, - 0.25, 0.4, - 0.4)
        for (dx in offsets) for (dz in offsets) {
            val rot = MathUtils.calcYawPitch(centre.add(dx, 0.0, dz))
            val look = Vec3.directionFromRotation(rot.pitch, rot.yaw)
            val landing = EtherwarpHelper.getEtherPos(p.position(), look, dist)
            if (landing.succeeded && landing.pos == target) {
                lastEtherCheck = true
                return rot
            }
        }
        lastEtherCheck = false
        return fallback
    }

    private fun releaseMovement() {
        mc.options.keyUp.isDown = false
        mc.options.keyJump.isDown = false
        mc.options.keySprint.isDown = false
    }

    private fun releaseKeys() {
        releaseMovement()
        mc.options.keyAttack.isDown = false
        PlayerUtils.toggleSneak(false)
    }

    // ------------------------------------------------------------------ route data

    private fun standOn(b: BlockPos) = Vec3(b.x + 0.5, b.y + 1.0, b.z + 0.5)

    private fun horizontal(a: Vec3, b: Vec3): Double {
        val dx = a.x - b.x
        val dz = a.z - b.z
        return sqrt(dx * dx + dz * dz)
    }

    private fun parse(o: JsonObject, roomName: String?): Step {
        val boss = roomName == "f7boss"
        val dir = RoomDirectionUtils.roomDirection()
        val corner = RoomDirectionUtils.roomCorner()
        fun block(a: JsonArray): BlockPos {
            val rel = BlockPos(a[0].asInt, a[1].asInt, a[2].asInt)
            return if (boss) rel else RoomRotationUtils.relativeToActual(rel, dir, corner)
        }
        fun blocks(key: String) = o.getAsJsonArray(key)?.map { block(it.asJsonArray) } ?: emptyList()
        fun point(a: JsonArray): Vec3 {
            val x = a[0].asDouble
            val y = a[1].asDouble
            val z = a[2].asDouble
            if (boss) return Vec3(x, y, z)
            val cx = corner?.getX() ?: 0.0
            val cz = corner?.getY() ?: 0.0
            return when (dir) {
                "W" -> Vec3(cx - z, y, cz + x)
                "N" -> Vec3(cx - x, y, cz - z)
                "E" -> Vec3(cx + z, y, cz - x)
                else -> Vec3(cx + x, y, cz + z)
            }
        }

        val pearls = ArrayList<Pair<Vec3, MathUtils.Rotation>>()
        val pearlPos = o.getAsJsonArray("enderpearls")
        val pearlAngles = o.getAsJsonArray("enderpearlangles")
        if (pearlPos != null && pearlAngles != null) for (i in 0 until minOf(pearlPos.size(), pearlAngles.size())) {
            val angle = pearlAngles[i].asJsonArray
            val pitch = angle[0].asFloat.coerceIn(- 90f, 90f)
            val yaw = if (boss) angle[1].asFloat else RotationUtils.relativeToActualYaw(angle[1].asFloat, dir)
            pearls += point(pearlPos[i].asJsonArray) to MathUtils.Rotation(yaw, pitch)
        }

        val secret = o.getAsJsonObject("secret")
        return Step(
            path = blocks("locations"),
            ethers = blocks("etherwarps").toMutableSet(),
            mines = blocks("mines").toMutableList(),
            tnts = blocks("tnts").toMutableList(),
            levers = blocks("interacts").toMutableList(),
            pearls = pearls,
            secretType = secret?.get("type")?.takeUnless { it.isJsonNull }?.asString,
            secretPos = secret?.getAsJsonArray("location")?.let(::block),
        )
    }

    @Suppress("unused")
    private fun ItemStack.isEtherItem() = EtherwarpHelper.getEtherwarpDistance(this) != null
}
//#endif
