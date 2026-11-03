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
    private val castMin by SliderSetting("Cast Delay Min", 4, 1, 60, 1, " ticks").withDescription("Shortest wait before casting again")
    private val castMax by SliderSetting("Cast Delay Max", 18, 1, 60, 1, " ticks")
    private val humanDelay by ToggleSetting("Human Delay", true).withDescription("Most delays land in the lower third of the range with the odd slow reaction, instead of evenly spread (like Reel Assist)")
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
    private val giveUp by SliderSetting("Give Up After", 5, 1, 30, 1, " s").withDescription("A creature that doesn't come within reach in this time is left alone and fishing goes on")
    private val weaponSlot by SliderSetting("Weapon Slot", 0, 0, 9, 1).withDescription("Hotbar slot (1-9) of the weapon. 0 = Hyperion / Astraea / Scylla / Valkyrie if found")
    private val hitDelay by SliderSetting("Hit Delay", 4, 1, 20, 1, " ticks")

    private val hotspots by ToggleSetting("Go To Hotspots", false).section("Hotspots").withDescription(
        "Finds the nearest fishing Hotspot, walks to a spot on the shore next to it and casts into it. When it goes away, moves on to the next one. With none around it fishes where you started"
    )
    private val hotspotRange by SliderSetting("Search Range", 64, 16, 160, 8, " blocks").withDescription("How far it looks for a Hotspot")
    private val hotspotRadius by SliderSetting("Hotspot Size", 3.0, 1.0, 6.0, 0.5, " blocks").withDescription("How close to the Hotspot's centre the hook has to land; it adjusts the aim until it does")
    private val stayHome by ToggleSetting("Return To Spot", true).withDescription("Walks back to the fishing spot if you get pushed away from it")

    private val failsafe by ToggleSetting("Stop On Anything Unexpected", true).section("Failsafe").withDescription(
        "Stops (doesn't try to hide anything): when the server teleports or turns you, changes your held item, or opens a menu by itself"
    )
    private val stopHealth by SliderSetting("Stop Below Health", 30, 0, 90, 5, "%").section("Safety").withDescription("Stops when your health drops below this (0 = never)")
    private val chatInfo by ToggleSetting("Chat Messages", true)
    private val statusHud by ToggleSetting("Status HUD", true)

    private enum class State { FIND, TRAVEL, CAST, WAIT_BITE, REEL, FIGHT }

    private val RANDOM = Random()
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
        if (! humanDelay.value) return rand(lo, hi)
        val span = (hi - lo).toDouble()
        val v = if (RANDOM.nextDouble() < 0.08) lo + span * (0.6 + 0.4 * RANDOM.nextDouble())
        else lo + span * 0.3 + RANDOM.nextGaussian() * span * 0.15
        return Math.round(v).toInt().coerceIn(lo, hi)
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
        if (AssistBridge.coreLoaded) return AssistBridge.turn(p, yaw, pitch, tol)
        // ease out: a share of the way each tick, never crawling
        fun step(d: Float): Float {
            val s = d * 0.32f
            return if (abs(s) < 0.6f) d.coerceIn(- 0.6f, 0.6f) else s.coerceIn(- 24f, 24f)
        }
        PlayerUtils.rotate(p.yRot + step(dy), p.xRot + step(dp))
        return false
    }

    /** The small eased head movements around the bobber while waiting. Also follows the player's own camera moves. */
    private fun lookMovement(p: LocalPlayer) {
        if (! lookJitter.value) return
        val expYaw = anchorYaw + offYaw
        val expPitch = anchorPitch + offPitch
        if (abs(wrap(p.yRot - expYaw)) > 0.3f || abs(p.xRot - expPitch) > 0.3f) {
            // the player turned the camera: fish where they look now
            anchorYaw = p.yRot - offYaw
            anchorPitch = p.xRot - offPitch
        }
        if (moveLen > 0) {
            moveTick++
            val t = (moveTick / moveLen.toFloat()).coerceAtMost(1f)
            val ease = t * t * (3 - 2 * t)
            offYaw = fromYaw + (toYaw - fromYaw) * ease
            offPitch = fromPitch + (toPitch - fromPitch) * ease
            p.yRot = anchorYaw + offYaw
            p.xRot = (anchorPitch + offPitch).coerceIn(- 90f, 90f)
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
        if (failsafe.value) {
            if (expectedSlot >= 0 && p.inventory.selectedSlot != expectedSlot) return failStop("your held item was changed")
            val sc = mc.gui.screen()
            if (sc is AbstractContainerScreen<*> && sc !is InventoryScreen) return failStop("a menu opened by itself (${sc.title.string})")
        }

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
            status = "pushed away, going back"
            return
        }

        // sea creatures first, the moment one comes up
        val mobs = if (killCreatures.value && mc.gui.screen() == null) creatures(p) else emptyList()
        if (state == State.FIGHT || mobs.isNotEmpty()) {
            if (mobs.isEmpty()) {
                state = State.CAST
                returning = true
                wait = delay(castMin.value, castMax.value)
                status = "back to fishing"
                return
            }
            if (state != State.FIGHT && p.fishing != null) rod()?.let { hold(it); use(p) } // pull the line in first
            state = State.FIGHT
            fight(p, mobs)
            return
        }

        // back to the fishing spot (eased) after a fight
        if (returning) {
            status = "looking back at the ${fluidName()}"
            if (! turn(p, anchorYaw + offYaw, anchorPitch + offPitch, 0.8f)) return
            returning = false
        }
        if (state == State.WAIT_BITE || state == State.CAST) lookMovement(p)

        if (wait > 0) {
            wait--
            return
        }
        val rod = rod() ?: return stop("no fishing rod in the hotbar")
        val hook = p.fishing

        when (state) {
            State.CAST -> {
                if (moveLen > 0) return // don't cast in the middle of a head movement
                hold(rod)
                if (hook != null) { // a line is still out: pull it in first
                    use(p)
                    wait = delay(castMin.value, castMax.value)
                    return
                }
                use(p)
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
                        wait = delay(castMin.value, castMax.value)
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
                        wait = delay(castMin.value, castMax.value)
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
                    wait = delay(castMin.value, castMax.value)
                    if (++groundFails >= 3) return stop("the hook keeps landing on the ground - look at the ${fluidName()}")
                    status = "hook on the ground, recasting ($groundFails/3)"
                    return
                }
                if (timer > castTimeout.value * 20) {
                    hold(rod)
                    use(p)
                    state = State.CAST
                    wait = delay(castMin.value, castMax.value)
                    status = "nothing bit, recasting"
                }
            }

            State.REEL -> {
                hold(rod)
                if (hook != null) use(p)
                state = State.CAST
                wait = delay(castMin.value, castMax.value)
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
        ownMoveTicks = 20 // our own AOTV / walking: not the server's doing
        expectedSlot = - 1
        when (AutoRoutes.travelTick(goal, 0.6)) {
            AutoRoutes.Travel.ARRIVED -> {
                AutoRoutes.releaseAll()
                val hs = hotspot
                if (hs != null) {
                    val rot = MathUtils.calcYawPitch(hs.position().add(0.0, 0.2, 0.0))
                    anchorYaw = rot.yaw
                    anchorPitch = (rot.pitch - 8f).coerceIn(- 30f, 60f) // a little above it: the hook flies in an arc
                }
                offYaw = 0f; offPitch = 0f; moveLen = 0
                returning = true
                state = State.CAST
                wait = delay(castMin.value, castMax.value)
                status = "at the spot"
            }
            AutoRoutes.Travel.STUCK -> if (++travelStuck > 5) {
                hotspot?.let { badHotspots += it.id }
                hotspot = null
                state = State.FIND
                status = "couldn't get there"
            }
            AutoRoutes.Travel.MOVING -> status = "going to ${if (hotspot != null) "the hotspot" else "the spot"} (${p.position().distanceTo(goal).toInt()}m)"
        }
    }

    /** Is the hook inside the hotspot? If not, nudge the aim towards it for the next cast (up to 8 tries, then accept). */
    private fun landedIn(p: LocalPlayer, hook: FishingHook, hs: ArmorStand): Boolean {
        val off = hook.position().subtract(hs.position())
        if (kotlin.math.sqrt(off.x * off.x + off.z * off.z) <= hotspotRadius.value || aimTries >= 8) return true
        aimTries++
        val eye = p.eyePosition
        fun flat(v: Vec3) = kotlin.math.sqrt((v.x - eye.x) * (v.x - eye.x) + (v.z - eye.z) * (v.z - eye.z))
        val tooFar = flat(hook.position()) - flat(hs.position())
        anchorPitch = (anchorPitch + (tooFar * 1.6).toFloat().coerceIn(- 6f, 6f)).coerceIn(- 45f, 70f)
        val yawHook = Math.toDegrees(kotlin.math.atan2(hook.z - eye.z, hook.x - eye.x)).toFloat()
        val yawHs = Math.toDegrees(kotlin.math.atan2(hs.z - eye.z, hs.x - eye.x)).toFloat()
        anchorYaw += wrap(yawHs - yawHook).coerceIn(- 10f, 10f)
        returning = true
        status = "hook landed outside the hotspot, adjusting aim ($aimTries)"
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
            val rot = MathUtils.calcYawPitch(far.boundingBox.center)
            turn(p, rot.yaw, rot.pitch, 3f)
            return
        }
        if (hype != null) {
            hold(hype)
            status = "Hyperion on ${close.size}"
            // look straight down so the blast covers everything around us
            if (! turn(p, p.yRot, 90f, 3f) || hitCooldown > 0) return
            hitCooldown = hitDelay.value
            ownMoveTicks = 20 // Wither Impact moves us: our doing, not the server's
            use(p)
            return
        }
        if (weaponSlot.value in 1 .. 9) hold(weaponSlot.value - 1)
        val target = close.minByOrNull { it.distanceToSqr(p) } ?: return
        status = "fighting ${target.name.string}"
        val rot = MathUtils.calcYawPitch(target.boundingBox.center)
        if (! turn(p, rot.yaw, rot.pitch, 4f) || hitCooldown > 0) return
        hitCooldown = hitDelay.value
        mc.gameMode?.attack(p, target)
        p.swing(InteractionHand.MAIN_HAND)
    }
}
//#endif
