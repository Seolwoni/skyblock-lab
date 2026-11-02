package com.github.noamm9.features.impl.routes

//#if CHEAT
import com.github.noamm9.config.types.DropdownSetting
import com.github.noamm9.config.types.KeybindSetting
import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.TickEvent
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
import net.minecraft.world.entity.ambient.Bat
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import xyz.yourboykyle.secretroutes.Main
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
    private val doSecret by ToggleSetting("Collect Secret", true).withDescription("Opens chests/levers/skulls and hits bats at the end of each step")
    private val chatInfo by ToggleSetting("Chat Messages", true)

    private const val REACH = 4.4

    private enum class Phase { MOVE, ETHER_AIM, ETHER_WAIT, ACTION_AIM, MINING, PEARL_WAIT, SECRET, DONE }

    private class Step(
        val path: List<BlockPos>,
        val ethers: Set<BlockPos>,
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
    private var lastPos: Vec3? = null

    override fun init() {
        register<TickEvent.Start> {
            if (toggleKey.isPressed() && mc.gui.screen() == null) {
                if (running) stop("stopped") else start()
            }
            if (running) runCatching(::tick).onFailure { stop("error: ${it.message}") }
        }
        register<WorldChangeEvent> { if (running) stop("world changed") }
    }

    override fun onDisable() {
        super.onDisable()
        if (running) stop(null)
    }

    private fun start() {
        if (! RouteBridge.routeActive()) return info("&cNo route to follow here.")
        running = true
        stepRef = null
        info("&aFollowing route. Press the key again to stop.")
    }

    private fun stop(reason: String?) {
        running = false
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
        if (! RouteBridge.routeActive()) return stop("finished (no more steps)")

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
                    PlayerUtils.rotateTo(Vec3.atCenterOf(t))
                    mc.options.keyAttack.isDown = true
                    if (++timer > 60) {
                        mc.options.keyAttack.isDown = false
                        s.mines.remove(t)
                        phase = Phase.MOVE
                    }
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
            s.tnts.remove(t)
            val slot = PlayerUtils.findHotbarSlot { it.skyblockId.contains("SUPERBOOM") } ?: return
            PlayerUtils.swapToSlot(slot)
            aimThen(Vec3.atCenterOf(t), use = true)
            return
        }
        if (doLevers.value) s.levers.firstOrNull { eye.distanceTo(Vec3.atCenterOf(it)) <= REACH }?.let { l ->
            s.levers.remove(l)
            aimThen(Vec3.atCenterOf(l), use = true)
            return
        }
        if (doPearls.value) s.pearls.firstOrNull { horizontal(pos, it.first) < 0.8 && abs(pos.y - it.first.y) < 1.5 }?.let { pearl ->
            s.pearls.remove(pearl)
            val slot = PlayerUtils.findHotbarSlot { it.skyblockId == "ENDER_PEARL" || it.hoverName.string.contains("Ender Pearl") } ?: return
            PlayerUtils.swapToSlot(slot)
            releaseMovement()
            PlayerUtils.rotate(pearl.second.yaw, pearl.second.pitch)
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
                PlayerUtils.rotateTo(Vec3(ether.x + 0.5, ether.y + 0.98, ether.z + 0.5))
                etherTarget = ether
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
            return
        }
        walkTowards(pos, goal)
    }

    private fun walkTowards(pos: Vec3, goal: Vec3) {
        val p = mc.player!!
        val yaw = Math.toDegrees(atan2(goal.z - pos.z, goal.x - pos.x)).toFloat() - 90f
        PlayerUtils.rotate(yaw, if (speed.value == 2) p.xRot else 10f)
        mc.options.keyUp.isDown = true
        mc.options.keySprint.isDown = sprint.value
        val needJump = autoJump.value && p.onGround() && ((goal.y - pos.y > 0.6 && horizontal(pos, goal) < 2.5) || p.horizontalCollision)
        mc.options.keyJump.isDown = needJump

        val moved = lastPos?.distanceTo(pos) ?: 1.0
        lastPos = pos
        stuckTicks = if (moved < 0.02) stuckTicks + 1 else 0
        if (stuckTicks > 40) stop("stuck - can't reach the next point")
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
                    PlayerUtils.rotateTo(bat.boundingBox.center)
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

    /** Rotate now, click on a later tick (the server needs the new rotation first). */
    private fun aimThen(target: Vec3, use: Boolean) {
        releaseMovement()
        PlayerUtils.rotateTo(target)
        if (zeroTick) {
            sendRotation()
            if (use) PlayerUtils.rightClick() else PlayerUtils.leftClick()
            return
        }
        actionUse = use
        phase = Phase.ACTION_AIM
        wait = actionDelay
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
            ethers = blocks("etherwarps").toSet(),
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
