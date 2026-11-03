package com.github.noamm9.features.impl.misc

//#if CHEAT
import com.github.noamm9.config.types.DropdownSetting
import com.github.noamm9.config.types.KeybindSetting
import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.MainThreadPacketReceivedEvent
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.features.impl.routes.AssistBridge
import com.github.noamm9.features.impl.routes.AutoRoutes
import com.github.noamm9.ui.notification.NotificationManager
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import com.github.noamm9.utils.ChatUtils.modMessage
import com.github.noamm9.utils.MathUtils
import com.github.noamm9.utils.PlayerUtils
import com.github.noamm9.utils.items.ItemUtils.skyblockId
import com.github.noamm9.utils.render.Render2D.drawString
import net.minecraft.client.player.LocalPlayer
import net.minecraft.tags.FluidTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.projectile.FishingHook
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import java.util.Random
import kotlin.math.abs

/**
 * Fishes by itself in water or lava, with Reel Assist's feel: delays that cluster low with the odd slow reaction,
 * small eased head movements around the bobber every 12-18 s, every turn eased (Assist Core's when installed),
 * and following your aim if you move the camera yourself. Sea creatures are killed as soon as they come up.
 */
object AutoFishing: Feature("Fishes by itself in water or lava: casts, reels in on the bite, recasts and kills sea creatures as soon as they come up. Human-like delays and smooth head movement (Reel Assist style).") {
    private val toggleKey by KeybindSetting("Start / Stop Key").withDescription("Look at the water or lava and press it to start, press again to stop")
    private val fluid by DropdownSetting("Fluid", 0, listOf("Auto", "Water", "Lava")).withDescription("Auto: works out water or lava from where the hook lands. Only used to tell a hook that fell on the ground")

    private val reelMin by SliderSetting("Reel Delay Min", 2, 1, 17, 1, " ticks").section("Timing").withDescription("Shortest wait after the bite before reeling in")
    private val reelMax by SliderSetting("Reel Delay Max", 9, 1, 17, 1, " ticks").withDescription("Longest wait after the bite (17 at most, so the fish doesn't get away)")
    private val castMin by SliderSetting("Cast Delay Min", 6, 1, 60, 1, " ticks").withDescription("Shortest wait before casting again")
    private val castMax by SliderSetting("Cast Delay Max", 34, 1, 80, 1, " ticks")
    private val hesitate by SliderSetting("Hesitation", 12, 0, 40, 1, "%").withDescription("Chance that a cast comes a little later than usual (0.3-1 s more)")
    private val aimWobble by SliderSetting("Cast Aim Wobble", 0.5, 0.0, 2.0, 0.1, "°").withDescription("Each cast is aimed a tiny bit differently, like a hand that never lands on the exact same spot")
    private val timingModel by DropdownSetting("Timing Model", 2, listOf("Even", "Reel Assist", "Human")).withDescription(
        "Even: any tick in the range equally. Reel Assist: mostly the lower third with the odd slow reaction. Human: skewed reaction times, a rhythm that drifts over minutes, each delay leaning on the last one, slight fatigue over time, no tick count twice in a row"
    )
    private val slipChance by SliderSetting("Slow Reactions", 6, 0, 30, 1, "%").withDescription("Human model: how often a clearly slow reaction happens").showIf { timingModel.value == 2 }
    private val drift by SliderSetting("Rhythm Drift", 20, 0, 50, 1, "%").withDescription("Human model: how far the overall speed wanders over minutes").showIf { timingModel.value == 2 }
    private val fatigue by SliderSetting("Fatigue", 8, 0, 40, 1, "%/h").withDescription("Human model: how much slower reactions get per hour of fishing (up to 2 hours)").showIf { timingModel.value == 2 }
    private val lookAtCatch by ToggleSetting("Look At The Catch", true).withDescription("After a sea creature or a good catch, waits a bit longer before casting again, like reading what came up")
    private val breakChance by SliderSetting("Short Breaks", 2.0, 0.0, 15.0, 0.5, "%").withDescription("Chance per cast of a 2-6 s pause before casting (checking chat, the inventory...)")
    private val castTimeout by SliderSetting("Recast After", 45, 10, 120, 5, " s").withDescription("Reels in and casts again if nothing bites for this long")
    private val groundRecast by ToggleSetting("Recast If Hook Lands On Ground", true).withDescription("Casts again when the hook settles somewhere that isn't water / lava; stops after 3 misses in a row")
    private val hookDive by ToggleSetting("Detect By Hook Dive", true).withDescription("Also reels in when the hook suddenly dives (vanilla bite), not only on the \"!!!\" sign")

    private val lookJitter by ToggleSetting("Look Movement", true).section("Look").withDescription("Small eased head movements around the bobber now and then, like a person holding the rod")
    private val jitterDegrees by SliderSetting("Movement Size", 1.0, 0.2, 4.0, 0.1, "°")
    private val jitterEvery by SliderSetting("Movement Every", 15, 3, 60, 1, " s").withDescription("On average; each wait is 80-120% of this")
    private val smoothTurns by ToggleSetting("Smooth Turning", true).withDescription("Eases every turn (to sea creatures and back). Uses Assist Core's turning when it's installed")

    private val rodSlot by SliderSetting("Rod Slot", 0, 0, 9, 1).section("Items").withDescription("Hotbar slot (1-9) of the rod. 0 = first fishing rod in the hotbar")
    private val killCreatures by ToggleSetting("Kill Sea Creatures", true).section("Sea Creatures")
    private val killRange by SliderSetting("Detect Range", 14.0, 4.0, 24.0, 0.5, " blocks").withDescription("A sea creature that comes up within this range is killed right away")
    private val blastRange by SliderSetting("Hyperion Reach", 6.0, 3.0, 10.0, 0.5, " blocks").withDescription("Casts once a creature is this close (the blast hits around you)")
    private val noticeMin by SliderSetting("Notice Delay Min", 8, 0, 40, 1, " ticks").withDescription("How long it takes to notice a sea creature that came up, before reacting")
    private val noticeMax by SliderSetting("Notice Delay Max", 26, 0, 60, 1, " ticks")
    private val giveUp by SliderSetting("Give Up After", 5, 1, 30, 1, " s").withDescription("A creature that doesn't come within reach in this time is left alone and fishing goes on")
    private val weaponSlot by SliderSetting("Weapon Slot", 0, 0, 9, 1).withDescription("Hotbar slot (1-9) of the weapon. 0 = Hyperion / Astraea / Scylla / Valkyrie if found")
    private val hitDelay by SliderSetting("Hit Delay", 4, 1, 20, 1, " ticks")

    private val hotspots by ToggleSetting("Go To Hotspots", false).section("Hotspots").withDescription(
        "Finds the nearest fishing Hotspot, walks to a spot on the shore next to it and casts into it. When it goes away, moves on to the next one. With none around it fishes where you started"
    )
    private val hopMin by SliderSetting("AOTV Gap Min", 5, 2, 40, 1, " ticks").withDescription("Shortest wait between AOTV hops on the way (same timing model as casting)")
    private val hopMax by SliderSetting("AOTV Gap Max", 18, 2, 60, 1, " ticks")
    private val walkSome by SliderSetting("Walk Instead", 15, 0, 80, 5, "%").withDescription("Chance to walk a stretch instead of hopping with the AOTV")
    private val hotspotRange by SliderSetting("Search Range", 64, 16, 160, 8, " blocks").withDescription("How far it looks for a Hotspot")
    private val hotspotRadius by SliderSetting("Hotspot Size", 2.5, 1.0, 6.0, 0.5, " blocks").withDescription("How close to the Hotspot's centre the hook has to land; it adjusts the aim until it does")
    private val stayHome by ToggleSetting("Return To Spot", true).withDescription("Walks back to the fishing spot if you get pushed away from it")

    private val failsafe by ToggleSetting("Stop On Anything Unexpected", true).section("Failsafe").withDescription(
        "Stops (doesn't try to hide anything): when the server teleports or turns you, changes your held item, or opens a menu by itself"
    )
    private val stopHealth by SliderSetting("Stop Below Health", 30, 0, 90, 5, "%").section("Safety").withDescription("Stops when your health drops below this (0 = never)")
    private val chatInfo by ToggleSetting("Chat Messages", true)
    private val statusHud by ToggleSetting("Status HUD", true)

    private enum class State { FIND, TRAVEL, CAST, WAIT_BITE, REEL, FIGHT }

    private val RANDOM = Random()
    private var timing = com.github.noamm9.utils.HumanTiming()
    private var hopThisStretch = true
    private var stretchLeft = 0
    private var justCaughtSomething = false
    private val WITHER_BLADES = setOf("HYPERION", "ASTRAEA", "SCYLLA", "VALKYRIE")
    private val NPC_CLASSES = setOf("Villager", "WanderingTrader")

    private var running = false
    private var state = State.CAST
    private var wait = 0
    private var timer = 0
    private var inFluidTicks = 0
    private var hitCooldown = 0
    private var status = "idle"
    private var lastMobIds = emptySet<Int>()
    private val waiting = HashMap<Int, Int>()
    private val ignored = HashSet<Int>()
    private var groundTicks = 0
    private var groundFails = 0
    private var lastHookPos: Vec3? = null

    // look: where we fish (anchor) + a small eased offset
    private var anchorYaw = 0f
    private var anchorPitch = 0f
    private var offYaw = 0f
    private var offPitch = 0f
    private var fromYaw = 0f
    private var fromPitch = 0f
    private var toYaw = 0f
    private var toPitch = 0f
    private var moveTick = 0
    private var moveLen = 0
    private var nextMoveIn = 0
    private var returning = false

    // hotspots / location
    private var hotspot: ArmorStand? = null
    private var stand: Vec3? = null
    private val badHotspots = HashSet<Int>()
    private var aimTries = 0
    private var travelStuck = 0
    private var ownMoveTicks = 0
    private var expectedSlot = - 1

    // safe walking (own path, avoids lava / water)
    private var path: List<BlockPos> = emptyList()
    private var pathIndex = 0
    private var pathGoal: Vec3? = null
    private var lastSafe: Vec3? = null
    private var lavaTicks = 0
    private var hopWait = 0
    private var repath = 0

    // aiming the cast
    private var aimTarget: Vec3? = null
    /** This cast's small hand wobble on top of the aim. */
    private var wobYaw = 0f
    private var wobPitch = 0f

    // noticing sea creatures
    private val noticeAt = HashMap<Int, Int>()
    private var tickCount = 0
    private var fightWait = 0
    private var castDownPitch = 0f

    override fun init() {
        register<TickEvent.Start> {
            if (toggleKey.isPressed() && mc.gui.screen() == null) {
                if (running) stop("stopped") else start()
            }
            if (running) runCatching(::tick).onFailure { stop("error: ${it.message}") }
        }
        register<WorldChangeEvent> { if (running) stop("world changed") }
        register<MainThreadPacketReceivedEvent.Pre> {
            if (! running || ! failsafe.value || ownMoveTicks > 0) return@register
            val name = event.packet.javaClass.simpleName
            if (event.packet is ClientboundPlayerPositionPacket || name.contains("PlayerRotation")) mc.execute { if (running) failStop("the server moved or turned you") }
        }

        hudElement("Auto Fishing Status", { statusHud.value }, { running }) { ctx, example ->
            val lines = if (example) listOf("§dAuto Fishing §aWAIT_BITE", "§7lava  §8waiting 6s") else listOf("§dAuto Fishing §a$state", "§7$status")
            lines.forEachIndexed { i, l -> ctx.drawString(l, 0, i * 10) }
            lines.maxOf { with(com.github.noamm9.utils.render.RenderHelper) { it.width() } } to lines.size * 10
        }
    }

    override fun onDisable() {
        super.onDisable()
        if (running) stop(null)
    }

    private fun info(msg: String) {
        if (chatInfo.value) modMessage(msg)
    }

    private fun start() {
        val p = mc.player ?: return
        if (rod() == null) return info("&cNo fishing rod in the hotbar.")
        anchorYaw = p.yRot
        anchorPitch = p.xRot
        timing = com.github.noamm9.utils.HumanTiming()
        wobYaw = 0f; wobPitch = 0f
        aimTarget = null
        noticeAt.clear()
        path = emptyList()
        offYaw = 0f; offPitch = 0f; moveLen = 0
        nextMoveIn = nextMoveDelay()
        running = true
        returning = false
        state = if (hotspots.value) State.FIND else State.CAST
        stand = if (hotspots.value) null else p.position()
        hotspot = null
        badHotspots.clear()
        aimTries = 0
        expectedSlot = - 1
        wait = 0
        groundFails = 0
        waiting.clear()
        ignored.clear()
        info("&aAuto Fishing started. Press the key again to stop.")
    }

    private fun failStop(why: String) {
        stop("stopped: $why")
        NotificationManager.push("Auto Fishing stopped", why)
        com.github.noamm9.utils.SoundUtils.playSound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value(), 1f, 0.6f)
    }

    private fun stop(reason: String?) {
        running = false
        AutoRoutes.releaseAll()
        mc.options.keyUse.isDown = false
        if (reason != null) info("&eAuto Fishing $reason.")
    }

    // ------------------------------------------------------------------ timing

    private fun rand(min: Int, max: Int) = if (max <= min) min else min + RANDOM.nextInt(max - min + 1)

    /** Reel Assist's delay: mostly the lower third of [min, max], 8% of the time a slow reaction. */
    private fun delay(min: Int, max: Int): Int {
        val lo = minOf(min, max)
        val hi = maxOf(min, max)
        if (hi <= lo) return lo
        if (timingModel.value == 0) return rand(lo, hi)
        if (timingModel.value == 2) {
            timing.slipChance = slipChance.value / 100.0
            timing.drift = drift.value / 100.0
            timing.fatiguePerHour = fatigue.value / 100.0
            return timing.next(lo, hi)
        }
        val span = (hi - lo).toDouble()
        val v = if (RANDOM.nextDouble() < 0.08) lo + span * (0.6 + 0.4 * RANDOM.nextDouble())
        else lo + span * 0.3 + RANDOM.nextGaussian() * span * 0.15
        return Math.round(v).toInt().coerceIn(lo, hi)
    }

    /** Wait before the next cast: the timing model, plus reading the catch and the odd short break. */
    private fun castWait(): Int {
        var t = delay(castMin.value, castMax.value)
        if (lookAtCatch.value && justCaughtSomething) t += timing.uniform(8, 25)
        justCaughtSomething = false
        t += timing.maybeBreak(breakChance.value, 40, 120)
        if (RANDOM.nextInt(100) < hesitate.value) t += timing.uniform(6, 20)
        return t
    }

    private fun nextMoveDelay() = (jitterEvery.value * 20 * (0.8 + 0.4 * RANDOM.nextDouble())).toInt()

    // ------------------------------------------------------------------ turning

    private fun wrap(d: Float): Float {
        var x = d % 360f
        if (x > 180f) x -= 360f
        if (x < - 180f) x += 360f
        return x
    }

    /** Eased turn towards yaw / pitch. True once within [tol] degrees. */
    private fun turn(p: LocalPlayer, yaw: Float, pitch: Float, tol: Float = 1.5f): Boolean {
        val dy = wrap(yaw - p.yRot)
        val dp = pitch - p.xRot
        if (abs(dy) <= tol && abs(dp) <= tol) return true
        if (! smoothTurns.value) {
            PlayerUtils.rotate(yaw, pitch)
            return true
        }
        return com.github.noamm9.utils.Humanize.turnTo(p, yaw, pitch, tol)
    }

    /** The small eased head movements around the bobber while waiting. Also follows the player's own camera moves. */
    private fun lookMovement(p: LocalPlayer) {
        if (! lookJitter.value) return
        val expYaw = anchorYaw + offYaw + wobYaw
        val expPitch = anchorPitch + offPitch + wobPitch
        if (abs(wrap(p.yRot - expYaw)) > 0.3f || abs(p.xRot - expPitch) > 0.3f) {
            // the player turned the camera: fish where they look now
            anchorYaw = p.yRot - offYaw - wobYaw
            anchorPitch = p.xRot - offPitch - wobPitch
        }
        if (moveLen > 0) {
            moveTick++
            val t = (moveTick / moveLen.toFloat()).coerceAtMost(1f)
            val ease = t * t * (3 - 2 * t)
            offYaw = fromYaw + (toYaw - fromYaw) * ease
            offPitch = fromPitch + (toPitch - fromPitch) * ease
            p.yRot = anchorYaw + offYaw + wobYaw
            p.xRot = (anchorPitch + offPitch + wobPitch).coerceIn(- 90f, 90f)
            if (t >= 1f) moveLen = 0
            return
        }
        if (--nextMoveIn > 0) return
        val d = jitterDegrees.value.toFloat()
        fromYaw = offYaw; fromPitch = offPitch
        toYaw = (RANDOM.nextFloat() * 2 - 1) * d
        toPitch = (RANDOM.nextFloat() * 2 - 1) * d * 0.6f
        moveTick = 0
        moveLen = rand(6, 14)
        nextMoveIn = nextMoveDelay()
    }

    // ------------------------------------------------------------------ main loop

    private fun rod(): Int? {
        if (rodSlot.value in 1 .. 9) return rodSlot.value - 1
        return PlayerUtils.findHotbarSlot { it.item == Items.FISHING_ROD }
    }

    private fun hold(slot: Int) {
        val p = mc.player ?: return
        if (p.inventory.selectedSlot != slot) p.inventory.selectedSlot = slot
        expectedSlot = slot
    }

    private fun use(p: LocalPlayer) {
        mc.gameMode?.useItem(p, InteractionHand.MAIN_HAND)
        p.swing(InteractionHand.MAIN_HAND)
    }

    private fun tick() {
        val p = mc.player ?: return stop("no player")
        if (stopHealth.value > 0 && p.health / p.maxHealth * 100f < stopHealth.value) return stop("health is low")
        if (hitCooldown > 0) hitCooldown--
        if (ownMoveTicks > 0) ownMoveTicks--
        timing.tick()
        if (failsafe.value) {
            if (expectedSlot >= 0 && p.inventory.selectedSlot != expectedSlot) return failStop("your held item was changed")
            val sc = mc.gui.screen()
            if (sc is AbstractContainerScreen<*> && sc !is InventoryScreen) return failStop("a menu opened by itself (${sc.title.string})")
        }

        tickCount++
        if (! p.isInLava && ! p.isInWater && p.onGround()) { lastSafe = p.position(); lavaTicks = 0 }
        if (p.isInLava) return escapeLava(p)

        when (state) {
            State.FIND -> return findHotspot(p)
            State.TRAVEL -> return travel(p)
            else -> {}
        }
        // pushed away from the spot: walk back (not while fighting, sea creatures come to us)
        val home = stand
        if (stayHome.value && home != null && state != State.FIGHT && p.position().distanceTo(home) > 3.0) {
            if (p.fishing != null) rod()?.let { hold(it); use(p) }
            state = State.TRAVEL
            path = emptyList()
            status = "pushed away, going back"
            return
        }

        // sea creatures first, the moment one comes up
        val seen = if (killCreatures.value && mc.gui.screen() == null) creatures(p) else emptyList()
        // a person needs a moment to notice something new: each creature gets its own notice time
        for (m in seen) noticeAt.getOrPut(m.id) { tickCount + delay(noticeMin.value, noticeMax.value) }
        if (noticeAt.size > 200) noticeAt.keys.retainAll(seen.map { it.id }.toSet())
        val mobs = seen.filter { (noticeAt[it.id] ?: 0) <= tickCount }
        if (state == State.FIGHT || mobs.isNotEmpty()) {
            if (mobs.isEmpty()) {
                state = State.CAST
                returning = true
                wait = castWait()
                status = "back to fishing"
                return
            }
            if (state != State.FIGHT) {
                if (p.fishing != null) rod()?.let { hold(it); use(p) } // pull the line in first
                fightWait = delay(3, 10) // then a beat before turning round
            }
            justCaughtSomething = true
            state = State.FIGHT
            if (fightWait > 0) { fightWait--; status = "noticed a sea creature"; return }
            fight(p, mobs)
            return
        }

        // back to the fishing spot (eased) after a fight
        if (returning) {
            status = "looking back at the ${fluidName()}"
            if (! turn(p, anchorYaw + offYaw + wobYaw, anchorPitch + offPitch + wobPitch, 0.8f)) return
            p.yRot = anchorYaw + offYaw + wobYaw // land exactly, so it isn't taken for a camera move
            p.xRot = (anchorPitch + offPitch + wobPitch).coerceIn(- 90f, 90f)
            returning = false
        }
        if (state == State.WAIT_BITE) lookMovement(p)

        if (wait > 0) {
            wait--
            return
        }
        val rod = rod() ?: return stop("no fishing rod in the hotbar")
        val hook = p.fishing

        when (state) {
            State.CAST -> {
                if (moveLen > 0) return // don't cast in the middle of a head movement
                // settle on this cast's aim (with its wobble) first, then land on it exactly
                val ty = anchorYaw + offYaw + wobYaw
                val tp = (anchorPitch + offPitch + wobPitch).coerceIn(- 90f, 90f)
                if (! turn(p, ty, tp, 0.35f)) return
                p.yRot = ty
                p.xRot = tp
                hold(rod)
                if (hook != null) { // a line is still out: pull it in first
                    use(p)
                    wait = castWait()
                    return
                }
                use(p)
                nextCastAim(p)
                state = State.WAIT_BITE
                timer = 0
                inFluidTicks = 0
                groundTicks = 0
                lastHookPos = null
                status = "cast"
            }

            State.WAIT_BITE -> {
                if (hook == null) {
                    if (++timer > 20) { // hook never came / line broke
                        state = State.CAST
                        wait = castWait()
                    }
                    return
                }
                timer++
                if (inFluid(hook)) inFluidTicks++
                status = "waiting ${timer / 20}s in ${fluidName()}" + if (hotspot != null) " (hotspot)" else ""
                if (hotspots.value) {
                    val hs = hotspot
                    if (hs != null && ! hs.isAlive) { // the hotspot ended: next one
                        hold(rod); use(p)
                        hotspot = null
                        state = State.FIND
                        status = "hotspot gone"
                        return
                    }
                    if (hs != null && inFluidTicks == 8 && ! landedIn(p, hook, hs)) {
                        hold(rod); use(p)
                        state = State.CAST
                        wait = castWait()
                        return
                    }
                    if (hs == null && timer % 200 == 0 && nearestHotspot(p) != null) { // one appeared
                        hold(rod); use(p)
                        state = State.FIND
                        return
                    }
                }
                if (bitten(hook)) {
                    state = State.REEL
                    wait = delay(reelMin.value, reelMax.value)
                    status = "bite!"
                    groundFails = 0
                    return
                }
                if (groundRecast.value && grounded(hook)) {
                    hold(rod)
                    use(p)
                    state = State.CAST
                    wait = castWait()
                    if (++groundFails >= 3) return stop("the hook keeps landing on the ground - look at the ${fluidName()}")
                    status = "hook on the ground, recasting ($groundFails/3)"
                    return
                }
                if (timer > castTimeout.value * 20) {
                    hold(rod)
                    use(p)
                    state = State.CAST
                    wait = castWait()
                    status = "nothing bit, recasting"
                }
            }

            State.REEL -> {
                hold(rod)
                if (hook != null) use(p)
                state = State.CAST
                wait = castWait()
                status = "reeled in"
            }

            State.FIGHT, State.FIND, State.TRAVEL -> {}
        }
    }

    private fun wantsLava(hook: FishingHook?) = when (fluid.value) {
        1 -> false
        2 -> true
        else -> hook != null && (hook.isInLava || nearFluid(hook, FluidTags.LAVA))
    }

    private fun fluidName() = if (wantsLava(mc.player?.fishing)) "lava" else "water"

    /** The bobber floats on top, so its own in-water flag flickers: look at its block and the two below too. */
    private fun nearFluid(hook: FishingHook, tag: net.minecraft.tags.TagKey<net.minecraft.world.level.material.Fluid>): Boolean {
        val pos = hook.blockPosition()
        for (dy in 0 downTo - 2) if (hook.level().getFluidState(pos.offset(0, dy, 0)).`is`(tag)) return true
        return false
    }

    private fun inFluid(hook: FishingHook) = when (fluid.value) {
        1 -> hook.isInWater || nearFluid(hook, FluidTags.WATER)
        2 -> hook.isInLava || nearFluid(hook, FluidTags.LAVA)
        else -> hook.isInWater || hook.isInLava || nearFluid(hook, FluidTags.WATER) || nearFluid(hook, FluidTags.LAVA)
    }

    /** True once the hook has clearly settled somewhere that isn't water / lava. */
    private fun grounded(hook: FishingHook): Boolean {
        if (hook.hookedIn == null && inFluid(hook)) {
            groundTicks = 0
            return false
        }
        val moved = lastHookPos?.distanceToSqr(hook.position()) ?: 1.0
        lastHookPos = hook.position()
        if (timer < 25) return false
        val stuck = hook.hookedIn != null || hook.onGround() || moved < 1.0E-4
        groundTicks = if (stuck) groundTicks + 1 else 0
        return groundTicks >= 10
    }

    /** SkyBlock puts a "!!!" stand over the hook on a bite (water and lava); vanilla pulls the hook down. */
    private fun bitten(hook: FishingHook): Boolean {
        val sign = hook.level().getEntitiesOfClass(ArmorStand::class.java, hook.boundingBox.inflate(1.5, 3.0, 1.5)) {
            it.customName?.string?.contains("!!!") == true
        }
        if (sign.isNotEmpty()) return true
        return hookDive.value && inFluidTicks > 10 && hook.deltaMovement.y < - 0.05
    }

    // ------------------------------------------------------------------ hotspots

    private fun nearestHotspot(p: LocalPlayer): ArmorStand? {
        val r = hotspotRange.value.toDouble()
        return p.level().getEntitiesOfClass(ArmorStand::class.java, p.boundingBox.inflate(r, 24.0, r)) {
            it.isAlive && it.id !in badHotspots && it.customName?.string?.contains("HOTSPOT", ignoreCase = true) == true
        }.minByOrNull { it.distanceToSqr(p) }
    }

    private fun findHotspot(p: LocalPlayer) {
        val hs = nearestHotspot(p)
        if (hs == null) {
            hotspot = null
            if (stand == null) stand = p.position()
            status = "no hotspot nearby, fishing here"
            state = State.CAST
            return
        }
        val spot = shoreSpot(p, hs)
        if (spot == null) {
            badHotspots += hs.id
            status = "no place to stand by that hotspot"
            return
        }
        hotspot = hs
        stand = spot
        aimTries = 0
        travelStuck = 0
        path = emptyList()
        state = State.TRAVEL
        info("&7Hotspot found &8(${p.position().distanceTo(hs.position()).toInt()} blocks)&7, going there.")
    }

    /** A block on the shore 4-10 blocks from the hotspot, with a clear view of it, as close to us as possible. */
    private fun shoreSpot(p: LocalPlayer, hs: ArmorStand): Vec3? {
        val level = p.level()
        val c = hs.blockPosition()
        var best: Vec3? = null
        var bestScore = Double.MAX_VALUE
        for (dx in - 10 .. 10) for (dz in - 10 .. 10) {
            val flat = kotlin.math.sqrt((dx * dx + dz * dz).toDouble())
            if (flat < 4.0 || flat > 10.0) continue
            for (dy in - 2 .. 5) {
                val feet = BlockPos(c.x + dx, c.y + dy, c.z + dz)
                val below = feet.below()
                if (level.getBlockState(below).getCollisionShape(level, below).isEmpty) continue
                if (! level.getFluidState(below).isEmpty) continue
                if (! level.getBlockState(feet).getCollisionShape(level, feet).isEmpty || ! level.getFluidState(feet).isEmpty) continue
                if (! level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty) continue
                if (nearLava(feet, 1)) continue // never stand right at a lava edge
                val eye = Vec3(feet.x + 0.5, feet.y + 1.62, feet.z + 0.5)
                val clip = level.clip(net.minecraft.world.level.ClipContext(eye, hs.position().add(0.0, 0.3, 0.0), net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, p))
                if (clip.type != net.minecraft.world.phys.HitResult.Type.MISS && clip.location.distanceTo(hs.position()) > 1.5) continue
                val pos = Vec3(feet.x + 0.5, feet.y.toDouble(), feet.z + 0.5)
                val score = pos.distanceTo(p.position()) + abs(flat - 7.0) * 2
                if (score < bestScore) { bestScore = score; best = pos }
                break
            }
        }
        return best
    }

    private fun travel(p: LocalPlayer) {
        val goal = stand ?: return run { state = State.FIND }
        ownMoveTicks = 20 // our own walking / AOTV: not the server's doing
        expectedSlot = - 1
        if (horizontalDist(p.position(), goal) < 0.6 && abs(p.y - goal.y) < 1.5) {
            stopWalking()
            path = emptyList()
            arrive(p)
            return
        }
        if (pathGoal != goal || path.isEmpty() || --repath <= 0) {
            pathGoal = goal
            path = findPath(p, BlockPos.containing(goal.x, goal.y + 0.1, goal.z)) ?: run {
                stopWalking()
                if (++travelStuck > 3) {
                    hotspot?.let { badHotspots += it.id }
                    hotspot = null
                    state = State.FIND
                    status = "no safe way there"
                }
                return
            }
            pathIndex = 0
            repath = 100
        }
        status = "going to ${if (hotspot != null) "the hotspot" else "the spot"} (${p.position().distanceTo(goal).toInt()}m)"
        walkPath(p)
    }

    private fun arrive(p: LocalPlayer) {
        val hs = hotspot
        if (hs != null) aimAt(p, hs) else aimTarget = null
        offYaw = 0f; offPitch = 0f; moveLen = 0
        returning = true
        state = State.CAST
        wait = castWait()
        status = "at the spot"
    }

    private fun horizontalDist(a: Vec3, b: Vec3) = kotlin.math.sqrt((a.x - b.x) * (a.x - b.x) + (a.z - b.z) * (a.z - b.z))

    private fun stopWalking() {
        mc.options.keyUp.isDown = false
        mc.options.keyJump.isDown = false
        mc.options.keySprint.isDown = false
    }

    private fun isLava(pos: BlockPos) = mc.level?.getFluidState(pos)?.`is`(FluidTags.LAVA) == true
    private fun isFluid(pos: BlockPos) = mc.level?.getFluidState(pos)?.isEmpty == false

    /** Lava within [r] blocks around (and just below) the feet. */
    private fun nearLava(feet: BlockPos, r: Int): Boolean {
        for (dx in - r .. r) for (dz in - r .. r) for (dy in - 2 .. 0) if (isLava(feet.offset(dx, dy, dz))) return true
        return false
    }

    private fun standable(feet: BlockPos): Boolean {
        val level = mc.level ?: return false
        val below = feet.below()
        if (level.getBlockState(below).getCollisionShape(level, below).isEmpty || isFluid(below)) return false
        if (! level.getBlockState(feet).getCollisionShape(level, feet).isEmpty || isFluid(feet)) return false
        val head = feet.above()
        return level.getBlockState(head).getCollisionShape(level, head).isEmpty && ! isFluid(head)
    }

    /** A* over standable blocks: never steps into or next to lava, keeps a block's distance from edges when it can. */
    private fun findPath(p: LocalPlayer, goal: BlockPos): List<BlockPos>? {
        val start = p.blockPosition()
        if (start.distManhattan(goal) > 160) return null
        data class N(val pos: BlockPos, val g: Double, val f: Double)
        val open = java.util.PriorityQueue<N>(compareBy { it.f })
        val came = HashMap<BlockPos, BlockPos>()
        val best = HashMap<BlockPos, Double>()
        fun h(b: BlockPos) = kotlin.math.sqrt(b.distSqr(goal))
        open += N(start, 0.0, h(start))
        best[start] = 0.0
        var expanded = 0
        val dirs = listOf(1 to 0, - 1 to 0, 0 to 1, 0 to - 1, 1 to 1, 1 to - 1, - 1 to 1, - 1 to - 1)
        while (open.isNotEmpty() && expanded++ < 6000) {
            val cur = open.poll()
            if (cur.pos.distManhattan(goal) <= 1 && abs(cur.pos.y - goal.y) <= 1) {
                val out = ArrayList<BlockPos>()
                var c: BlockPos? = cur.pos
                while (c != null) { out += c; c = came[c] }
                out.reverse()
                return out
            }
            if (cur.g > (best[cur.pos] ?: Double.MAX_VALUE)) continue
            for ((dx, dz) in dirs) {
                for (dy in intArrayOf(0, 1, - 1, - 2, - 3)) {
                    val n = cur.pos.offset(dx, dy, dz)
                    if (! standable(n)) continue
                    if (dx != 0 && dz != 0 && (! standable(cur.pos.offset(dx, 0, 0)) || ! standable(cur.pos.offset(0, 0, dz)))) continue
                    if (dy == 1 && ! mc.level!!.getBlockState(cur.pos.above(2)).getCollisionShape(mc.level!!, cur.pos.above(2)).isEmpty) continue
                    if (nearLava(n, 0)) continue
                    val step = (if (dx != 0 && dz != 0) 1.414 else 1.0) + (if (dy != 0) 0.5 else 0.0) + (if (nearLava(n, 1)) 6.0 else 0.0)
                    val g = cur.g + step
                    if (g < (best[n] ?: Double.MAX_VALUE)) {
                        best[n] = g
                        came[n] = cur.pos
                        open += N(n, g, g + h(n))
                    }
                    break // one height per direction (the first that fits)
                }
            }
        }
        return null
    }

    /** Walks the path node to node with eased turns; hops along long safe straight stretches with the AOTV. */
    private fun walkPath(p: LocalPlayer) {
        if (pathIndex >= path.size) return
        // skip nodes we've already passed
        while (pathIndex < path.size - 1 && horizontalDist(p.position(), Vec3.atBottomCenterOf(path[pathIndex])) < 0.7) pathIndex++
        val node = path[pathIndex]
        val target = Vec3.atBottomCenterOf(node)

        // safety: never walk into a block that has lava in it or under it
        val ahead = BlockPos.containing(p.x + p.deltaMovement.x * 4, p.y - 0.5, p.z + p.deltaMovement.z * 4)
        if (isLava(ahead) || isLava(ahead.above()) || (mc.level!!.getBlockState(ahead).isAir && isLava(ahead.below()))) {
            stopWalking()
            repath = 0
            status = "lava ahead, finding another way"
            return
        }

        if (hopWait > 0) hopWait--
        if (tryHop(p)) return

        val yaw = Math.toDegrees(kotlin.math.atan2(target.z - p.z, target.x - p.x)).toFloat() - 90f
        turn(p, yaw, 8f + RANDOM.nextFloat() * 6f, 12f)
        val dy = node.y - p.blockPosition().y
        mc.options.keyUp.isDown = abs(wrap(yaw - p.yRot)) < 50f
        mc.options.keyJump.isDown = p.onGround() && (dy > 0 || p.horizontalCollision)
        mc.options.keySprint.isDown = path.size - pathIndex > 4 && ! nearLava(p.blockPosition(), 2)
    }

    /** AOTV to a path node 9-12 blocks ahead when the stretch is straight, level, in sight and away from lava. */
    private fun tryHop(p: LocalPlayer): Boolean {
        if (hopWait > 0 || ! p.onGround() || RANDOM.nextInt(100) < walkSome.value) return false
        val slot = PlayerUtils.findHotbarSlot { it.skyblockId == "ASPECT_OF_THE_VOID" || it.skyblockId == "ASPECT_OF_THE_END" } ?: return false
        val from = p.position()
        var pick = - 1
        for (i in pathIndex until path.size) {
            val d = horizontalDist(from, Vec3.atBottomCenterOf(path[i]))
            if (d > 12.0) break
            if (abs(path[i].y - p.blockPosition().y) > 1) break
            if (d >= 9.0) pick = i
        }
        if (pick < 0) return false
        val node = path[pick]
        if (nearLava(node, 1)) return false
        // every node on the way has to sit on the straight line (no corner to cut over a gap)
        val end = Vec3.atBottomCenterOf(node)
        for (i in pathIndex .. pick) {
            val c = Vec3.atBottomCenterOf(path[i])
            val t = ((c.x - from.x) * (end.x - from.x) + (c.z - from.z) * (end.z - from.z)) / ((end.x - from.x).let { it * it } + (end.z - from.z).let { it * it }).coerceAtLeast(0.01)
            val px = from.x + (end.x - from.x) * t
            val pz = from.z + (end.z - from.z) * t
            if (kotlin.math.sqrt((c.x - px) * (c.x - px) + (c.z - pz) * (c.z - pz)) > 1.2) return false
        }
        val aim = end.add(0.0, 1.0, 0.0)
        val clip = p.level().clip(net.minecraft.world.level.ClipContext(p.eyePosition, aim, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, p))
        if (clip.type != net.minecraft.world.phys.HitResult.Type.MISS) return false
        hold(slot)
        stopWalking()
        val rot = MathUtils.calcYawPitch(aim)
        if (! turn(p, rot.yaw, rot.pitch, 2f)) return true
        use(p)
        hopWait = delay(hopMin.value, hopMax.value)
        pathIndex = pick
        return true
    }

    /** In lava: jump and walk back to the last safe ground; stop if it doesn't work out. */
    private fun escapeLava(p: LocalPlayer) {
        ownMoveTicks = 20
        if (p.fishing != null) rod()?.let { hold(it); use(p) }
        val back = lastSafe
        status = "in lava, getting out"
        mc.options.keyJump.isDown = true
        if (back != null) {
            val yaw = Math.toDegrees(kotlin.math.atan2(back.z - p.z, back.x - p.x)).toFloat() - 90f
            PlayerUtils.rotate(yaw, 0f)
            mc.options.keyUp.isDown = true
        }
        if (++lavaTicks > 80) {
            stopWalking()
            failStop("fell into lava and couldn't get out")
            return
        }
        state = State.TRAVEL
        repath = 0
    }

    // ------------------------------------------------------------------ aiming the cast

    /** The fluid surface height at (or just below) [pos]. */
    private fun surfaceY(pos: BlockPos): Double {
        for (dy in 2 downTo - 6) {
            val b = pos.offset(0, dy, 0)
            if (isFluid(b) && ! isFluid(b.above())) return b.y + 0.88
        }
        return pos.y.toDouble()
    }

    /**
     * Where a hook cast from here with this yaw / pitch comes down on a surface at [surface] (vanilla throw:
     * 0.6 blocks/tick along the look, 0.92 drag, 0.03 gravity). Null if it hits a block on the way.
     */
    private fun landing(p: LocalPlayer, yaw: Float, pitch: Float, surface: Double): Vec3? {
        val r = Math.PI / 180.0
        val f2 = kotlin.math.cos(- yaw * r - Math.PI)
        val f3 = kotlin.math.sin(- yaw * r - Math.PI)
        val f4 = - kotlin.math.cos(- pitch * r)
        val f5 = kotlin.math.sin(- pitch * r)
        var pos = Vec3(p.x - f3 * 0.3, p.eyeY, p.z - f2 * 0.3)
        var v = Vec3(- f3, (- (f5 / f4)).coerceIn(- 5.0, 5.0), - f2)
        v = v.scale(0.6 / v.length() + 0.5) // vanilla: 0.6 / length + 0.5 (the 0.5 has a tiny random spread)
        val level = p.level()
        repeat(160) {
            val next = pos.add(v)
            val hit = level.clip(net.minecraft.world.level.ClipContext(pos, next, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, p))
            if (hit.type != net.minecraft.world.phys.HitResult.Type.MISS) return null
            if (next.y <= surface && v.y < 0) return next
            pos = next
            v = Vec3(v.x * 0.92, v.y * 0.92 - 0.03, v.z * 0.92)
        }
        return null
    }

    /** Finds the yaw / pitch whose throw lands on [target]. */
    private fun solveAim(p: LocalPlayer, target: Vec3): Pair<Float, Float>? {
        val surface = surfaceY(BlockPos.containing(target))
        val yaw = Math.toDegrees(kotlin.math.atan2(target.z - p.z, target.x - p.x)).toFloat() - 90f
        val tries = ArrayList<Pair<Float, Double>>()
        var pitch = - 55f
        while (pitch <= 50f) {
            landing(p, yaw, pitch, surface)?.let { tries += pitch to horizontalDist(it, target) }
            pitch += 0.25f
        }
        val bestErr = tries.minOfOrNull { it.second } ?: return null
        // a short and a high throw can land on the same spot: take the flatter one, like people do
        val best = tries.filter { it.second <= bestErr + 0.25 }.maxByOrNull { it.first } ?: return null
        return yaw to best.first
    }

    private fun aimAt(p: LocalPlayer, hs: ArmorStand) {
        aimTarget = Vec3(hs.x, hs.y, hs.z)
        aimTries = 0
        val sol = solveAim(p, aimTarget!!)
        if (sol != null) {
            anchorYaw = sol.first
            anchorPitch = sol.second
        }
        else {
            val rot = MathUtils.calcYawPitch(hs.position())
            anchorYaw = rot.yaw
            anchorPitch = (rot.pitch - 8f).coerceIn(- 30f, 60f)
        }
        nextCastAim(p)
    }

    /** This cast's aim: the solved aim plus a small hand wobble. */
    private fun nextCastAim(@Suppress("UNUSED_PARAMETER") p: LocalPlayer) {
        val w = aimWobble.value.toFloat()
        wobYaw = (RANDOM.nextFloat() * 2 - 1) * w
        wobPitch = (RANDOM.nextFloat() * 2 - 1) * w * 0.6f
    }

    /** Is the hook inside the hotspot? If not, move the aim point by the miss and solve the throw again (8 tries). */
    private fun landedIn(p: LocalPlayer, hook: FishingHook, hs: ArmorStand): Boolean {
        val off = hook.position().subtract(hs.position())
        if (kotlin.math.sqrt(off.x * off.x + off.z * off.z) <= hotspotRadius.value || aimTries >= 8) return true
        aimTries++
        val base = aimTarget ?: Vec3(hs.x, hs.y, hs.z)
        aimTarget = Vec3(base.x - off.x * 0.85, base.y, base.z - off.z * 0.85)
        solveAim(p, aimTarget!!)?.let { (y, pi) -> anchorYaw = y; anchorPitch = pi }
        nextCastAim(p)
        returning = true
        status = "hook landed beside the hotspot, re-aiming ($aimTries)"
        return false
    }

    // ------------------------------------------------------------------ sea creatures

    private fun creatures(p: LocalPlayer): List<LivingEntity> {
        val r = killRange.value
        return p.level().getEntitiesOfClass(Mob::class.java, p.boundingBox.inflate(r, 6.0, r)) { e ->
            e.isAlive && e.health > 0f && e.id !in ignored && e.javaClass.simpleName !in NPC_CLASSES && e.distanceTo(p) <= r
        }
    }

    private fun fight(p: LocalPlayer, mobs: List<LivingEntity>) {
        val hype = if (weaponSlot.value in 1 .. 9) null else PlayerUtils.findHotbarSlot { it.skyblockId in WITHER_BLADES }
        val reach = if (hype != null) blastRange.value else 3.2
        for (m in mobs) {
            if (p.distanceTo(m) <= reach) waiting.remove(m.id)
            else if ((waiting.merge(m.id, 1, Int::plus) ?: 0) > giveUp.value * 20) {
                ignored += m.id
                waiting.remove(m.id)
            }
        }
        val close = mobs.filter { p.distanceTo(it) <= reach }
        if (close.isEmpty()) {
            val far = mobs.minByOrNull { it.distanceToSqr(p) } ?: return
            status = "waiting for ${far.name.string}"
            // keep an eye on it loosely: only turn when it's well off to the side, and not onto its exact middle
            val rot = MathUtils.calcYawPitch(com.github.noamm9.utils.Humanize.aimPoint(far.boundingBox.center, 0.6))
            if (abs(wrap(rot.yaw - p.yRot)) > 22f || abs(rot.pitch - p.xRot) > 18f) turn(p, rot.yaw, rot.pitch, 9f)
            return
        }
        if (hype != null) {
            hold(hype)
            status = "Hyperion on ${close.size}"
            // look straight down so the blast covers everything around us
            if (castDownPitch == 0f) castDownPitch = 80f + RANDOM.nextFloat() * 9f
            if (! turn(p, p.yRot, castDownPitch, 3f) || hitCooldown > 0) return
            castDownPitch = 0f
            hitCooldown = hitDelay.value
            ownMoveTicks = 20 // Wither Impact moves us: our doing, not the server's
            use(p)
            return
        }
        if (weaponSlot.value in 1 .. 9) hold(weaponSlot.value - 1)
        val target = close.minByOrNull { it.distanceToSqr(p) } ?: return
        status = "fighting ${target.name.string}"
        val rot = MathUtils.calcYawPitch(com.github.noamm9.utils.Humanize.aimPoint(target.boundingBox.center, 0.25))
        if (! turn(p, rot.yaw, rot.pitch, 4f) || hitCooldown > 0) return
        hitCooldown = hitDelay.value
        mc.gameMode?.attack(p, target)
        p.swing(InteractionHand.MAIN_HAND)
    }
}
//#endif
