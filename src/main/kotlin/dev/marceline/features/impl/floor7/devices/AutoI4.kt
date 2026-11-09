package dev.marceline.features.impl.floor7.devices

//#if CHEAT

import dev.marceline.config.types.*
import dev.marceline.event.impl.*
import dev.marceline.features.Feature
import dev.marceline.features.impl.floor7.MelodyDisplay
import dev.marceline.ui.utils.Animation.Companion.easeInOutCubic
import dev.marceline.utils.*
import dev.marceline.utils.ActionUtils.queue
import dev.marceline.utils.ActionUtils.waitTicks
import dev.marceline.utils.MathUtils.calcYawPitch
import dev.marceline.utils.MathUtils.interpolateYaw
import dev.marceline.utils.MathUtils.lerp
import dev.marceline.utils.PlayerUtils.leapAction
import dev.marceline.utils.Aim
import dev.marceline.utils.AimPriority
import dev.marceline.utils.dungeons.DungeonListener
import dev.marceline.utils.dungeons.enums.DungeonClass
import dev.marceline.utils.location.LocationUtils
import kotlinx.coroutines.*
import net.minecraft.core.BlockPos
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import kotlin.math.abs
import kotlin.math.min

object AutoI4: Feature("Fully Automated I4") {
    /** The timed bow turns onto the emeralds. */
    private val AIM_I4 = AimPriority.CRITICAL

    private val rotationTime by SliderSetting("Rotation Time", 170, 0, 250, 1).withDescription("Time (ms) to interpolate rotations when aiming at dev block targets. &eSet to 0 to disable the auto rotation.").section("Shooting")
    private val predictSetting by ToggleSetting("Predictions", true).withDescription("Enables prediction logic to aim at the next target block.")

    private val rodSetting by ToggleSetting("Auto Rod", true).section("Actions")
    private val maskSetting by ToggleSetting("Auto Mask", true)
    private val leapSetting by ToggleSetting("Auto Leap", true)
    private val leapToMelody by ToggleSetting("Leap To Melody").showIf { leapSetting.value }.withDescription("AutoLeaps to however has melody terminal open. overwrites the 'Leap Priority' option")
    private val leapPriorities = listOf(DungeonClass.Tank, DungeonClass.Mage, DungeonClass.Healer, DungeonClass.Archer)

    private val swapOrder by DropdownSetting("Swap Order", 0, listOf("Rod First", "Mask First")).showIf { rodSetting.value && maskSetting.value }
    private val preferredLeapClass by DropdownSetting("Leap Priority", 0, leapPriorities.map { it.name }).showIf { leapSetting.value }

    private const val GOLDER_START_MESSAGE = "[BOSS] Goldor: Who dares trespass into my domain?"

    private val doneCoords = ConcurrentHashMap.newKeySet<BlockPos>()
    private val tickTimer = AtomicInteger(- 1)
    @Volatile private var melodyLeapTargetName: String? = null
    @Volatile private var hasChangedMask = false
    @Volatile private var hasRodSwapped = false
    @Volatile private var hasAlerted = false
    @Volatile private var hasLeaped = false

    private val activeEmerald = AtomicReference<BlockPos?>(null)
    private val lastAttemptTime = AtomicLong(0L)
    @Volatile private var watchdogJob: Job? = null

    override fun init() {
        register<ChatMessageEvent> {
            if (! LocationUtils.inBoss || LocationUtils.dungeonFloorNumber != 7) return@register
            val msg = event.unformattedText
            MelodyDisplay.parseMelodyMessage(msg)?.let { melody ->
                melodyLeapTargetName = melody.name
            }

            if (msg == GOLDER_START_MESSAGE) {
                ThreadUtils.setTimeout(30_000L) { tickTimer.set(- 1) }
                tickTimer.set(0)
            }
            else if (msg.contains("completed a device!")) {
                if (! (tickTimer.get() >= 0 && I4Helper.isOnDev() && leapSetting.value)) return@register
                if (I4Helper.DEVICE_DONE_REGEX.find(msg)?.groupValues?.get(1) != mc.user.name) return@register
                onComplete()
            }
        }

        register<TickEvent.Server> {
            if (tickTimer.get() == - 1) return@register
            val timer = tickTimer.incrementAndGet()
            if (! I4Helper.isOnDev()) return@register

            when (timer) {
                203 if leapSetting.value -> {
                    ChatUtils.debug("i4", "saveLeap")
                    queue(4, false, ::saveLeap)
                }

                140 -> {
                    if (rodSetting.value && maskSetting.value) {
                        if (swapOrder.value == 0 && ! hasChangedMask) {
                            hasChangedMask = true
                            ChatUtils.debug("i4", "changeMaskAction")
                            queue(3, true, PlayerUtils::changeMaskAction)
                        }
                        else if (! hasRodSwapped) {
                            hasRodSwapped = true
                            queue(2, true, PlayerUtils::rodSwap)
                            ChatUtils.debug("i4", "rodSwap")
                        }
                    }
                    else if (maskSetting.value && ! hasChangedMask) {
                        hasChangedMask = true
                        ChatUtils.debug("i4", "changeMaskAction")
                        queue(3, true, PlayerUtils::changeMaskAction)
                    }
                    else if (rodSetting.value && ! hasRodSwapped) {
                        hasRodSwapped = true
                        queue(2, true, PlayerUtils::rodSwap)
                        ChatUtils.debug("i4", "rodSwap")
                    }
                }

                70 -> {
                    if (rodSetting.value && maskSetting.value) {
                        if (swapOrder.value == 0) {
                            if (! hasRodSwapped) {
                                hasRodSwapped = true
                                queue(2, true, PlayerUtils::rodSwap)
                                ChatUtils.debug("i4", "rodSwap")
                            }
                        }
                        else {
                            hasChangedMask = true
                            ChatUtils.debug("i4", "changeMaskAction")
                            queue(3, true, PlayerUtils::changeMaskAction)
                        }

                    }
                    else if (rodSetting.value && ! hasRodSwapped) {
                        hasRodSwapped = true
                        queue(2, true, PlayerUtils::rodSwap)
                        ChatUtils.debug("i4", "rodSwap")
                    }
                    else if (maskSetting.value) {
                        hasChangedMask = true
                        queue(3, true, PlayerUtils::changeMaskAction)
                        ChatUtils.debug("i4", "changeMaskAction")
                    }
                }
            }
        }

        register<BlockChangeEvent> {
            if (! LocationUtils.inBoss || LocationUtils.dungeonFloorNumber != 7) return@register
            if (! I4Helper.isOnDev()) {
                val timer = tickTimer.get()
                reset()
                tickTimer.set(timer)
                return@register
            }

            if (event.pos !in I4Helper.devBlocks) return@register
            if (event.oldBlock == Blocks.EMERALD_BLOCK && event.newBlock == Blocks.DYED_TERRACOTTA.blue()) {
                doneCoords.add(event.pos)
                if (activeEmerald.get() == event.pos) {
                    activeEmerald.set(null)
                }
                return@register
            }

            if (! player.mainHandItem.item.equalsOneOf(Items.BOW, Items.FISHING_ROD)) return@register
            if (event.newBlock != Blocks.EMERALD_BLOCK) return@register

            activeEmerald.set(event.pos)
            lastAttemptTime.set(DungeonListener.currentTime)
            checkStall()

            if (rotationTime.value > 0) queue(1) {
                shootAtBlock(event.pos)

                if (predictSetting.value) {
                    val next = I4Helper.prediction ?: I4Helper.getPredictionTarget(event.pos, doneCoords) ?: return@queue
                    shootAtBlock(next)
                }
            }
        }
    }

    override fun onDisable() {
        super.onDisable()
        reset()
        Aim.release(AutoI4)
    }

    private fun checkStall() {
        if (watchdogJob?.isActive == true) return
        if (rotationTime.value <= 0) return
        watchdogJob = scope.launch {
            while (isActive && enabled && I4Helper.isOnDev()) {
                delay(100)

                val target = activeEmerald.get() ?: continue
                if (WorldUtils.getBlockAt(target) != Blocks.EMERALD_BLOCK) continue
                if (DungeonListener.currentTime - lastAttemptTime.get() < 20) continue

                lastAttemptTime.set(DungeonListener.currentTime)
                queue(2) { shootAtBlock(target) }
            }
        }

        watchdogJob?.invokeOnCompletion { watchdogJob = null }
    }

    private fun getEmerald(vararg exclude: BlockPos?) = I4Helper.devBlocks.find {
        if (it in exclude) return@find false
        if (it in doneCoords) return@find false
        if (it == activeEmerald.get()) return@find false
        WorldUtils.getBlockAt(it) == Blocks.EMERALD_BLOCK
    }

    private suspend fun shootAtBlock(pos: BlockPos) {
        if (! enabled) return
        val (yaw, pitch) = calcYawPitch(when (pos) {
            I4Helper.prediction -> I4Helper.predictionAim !!
            I4Helper.target -> I4Helper.targetAim !!
            else -> I4Helper.getTargetVector(pos, doneCoords)
        })

        val block = suspend {
            waitTicks()
            PlayerUtils.rightClick()
        }

        getEmerald(pos)?.let { newer ->
            activeEmerald.set(newer)
            lastAttemptTime.set(DungeonListener.currentTime)
            return shootAtBlock(newer)
        }

        val currentYaw = MathUtils.normalizeYaw(player.yRot)
        val currentPitch = MathUtils.normalizePitch(player.xRot)
        val targetYaw = MathUtils.normalizeYaw(yaw)
        val targetPitch = MathUtils.normalizePitch(pitch)
        val tolerance = 1f

        if (abs(currentYaw - targetYaw) <= tolerance && abs(currentPitch - targetPitch) <= tolerance) return block()

        val startTime = System.currentTimeMillis()
        val duration = rotationTime.value.toDouble()
        while (true) {
            if (! enabled) return
            val newerDuring = getEmerald(pos)
            if (newerDuring != null) {
                doneCoords.add(pos)
                activeEmerald.set(newerDuring)
                lastAttemptTime.set(DungeonListener.currentTime)
                return shootAtBlock(newerDuring)
            }

            val elapsed = System.currentTimeMillis() - startTime
            val progress = min(elapsed.toDouble() / duration, 1.0)

            if (progress >= 1) {
                if (! PlayerUtils.aimView(AutoI4, AIM_I4, targetYaw, targetPitch)) return
                block()
                break
            }

            val easedProgress = easeInOutCubic(progress).toFloat()
            val newYaw = interpolateYaw(currentYaw, targetYaw, easedProgress)
            val newPitch = lerp(currentPitch, targetPitch, easedProgress).toFloat()
            if (! PlayerUtils.aimView(AutoI4, AIM_I4, newYaw, newPitch)) return
            delay(1)
        }
    }

    private suspend fun saveLeap() {
        if (! leapSetting.value) return
        if (hasLeaped) return
        hasLeaped = true
        tickTimer.set(- 1)

        val aliveTeammates = DungeonListener.dungeonTeammatesNoSelf.filterNot { it.isDead }
        val preferredClass = leapPriorities[preferredLeapClass.value]
        val melodyTarget = melodyLeapTargetName?.takeIf { leapToMelody.value }?.let { name ->
            aliveTeammates.find { it.name == name }
        }

        val target = melodyTarget
            ?: aliveTeammates.find { it.clazz == preferredClass }
            ?: leapPriorities.firstNotNullOfOrNull { priority ->
                aliveTeammates.find { it.clazz == priority }
            } ?: return

        leapAction(target)
        while (I4Helper.isOnDev()) delay(50)
        ActionUtils.reset()
    }

    private fun onComplete() {
        if (hasAlerted) return
        hasAlerted = true
        tickTimer.set(- 1)
        queue(4, false, ::saveLeap)
    }

    fun reset() {
        tickTimer.set(- 1)
        doneCoords.clear()
        hasChangedMask = false
        hasRodSwapped = false
        hasLeaped = false
        hasAlerted = false
        melodyLeapTargetName = null
        activeEmerald.set(null)
        lastAttemptTime.set(0)
        watchdogJob?.cancel()
        watchdogJob = null
    }
}
//#endif