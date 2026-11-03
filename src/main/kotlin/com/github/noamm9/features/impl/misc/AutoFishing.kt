package com.github.noamm9.features.impl.misc

//#if CHEAT
import com.github.noamm9.config.types.KeybindSetting
import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.utils.ChatUtils.modMessage
import com.github.noamm9.utils.MathUtils
import com.github.noamm9.utils.PlayerUtils
import com.github.noamm9.utils.items.ItemUtils.skyblockId
import com.github.noamm9.utils.render.Render2D.drawString
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.FishingHook
import net.minecraft.world.item.Items

/**
 * Fishes by itself: casts, waits for the bite (SkyBlock's "!!!" above the hook, or the hook diving),
 * reels in, recasts. Sea creatures that pile up are killed with the Hyperion (cast looking down)
 * or a melee weapon, then it goes back to the rod and the same spot.
 */
object AutoFishing: Feature("Fishes by itself: casts, reels in on the bite, recasts, and kills the sea creatures that come up (Hyperion or a melee weapon).") {
    private val toggleKey by KeybindSetting("Start / Stop Key").withDescription("Look at the water and press it to start, press again to stop")
    private val reelMin by SliderSetting("Reel Delay Min", 2, 0, 10, 1, " ticks").section("Timing").withDescription("Shortest wait after the bite before reeling in")
    private val reelMax by SliderSetting("Reel Delay Max", 5, 0, 15, 1, " ticks").withDescription("Longest wait after the bite before reeling in")
    private val recastDelay by SliderSetting("Recast Delay", 8, 2, 40, 1, " ticks").withDescription("Wait after reeling in before casting again")
    private val castTimeout by SliderSetting("Recast After", 45, 10, 120, 5, " s").withDescription("Reels in and casts again if nothing bites for this long")
    private val hookMotion by ToggleSetting("Detect By Hook Dive", true).withDescription("Also reels in when the hook suddenly dives (vanilla bite), not only on the \"!!!\" sign")
    private val rodSlot by SliderSetting("Rod Slot", 0, 0, 9, 1).section("Items").withDescription("Hotbar slot (1-9) of the rod. 0 = first fishing rod in the hotbar")
    private val killCreatures by ToggleSetting("Kill Sea Creatures", true).section("Sea Creatures")
    private val killRange by SliderSetting("Detect Range", 14.0, 4.0, 24.0, 0.5, " blocks").withDescription("A sea creature that comes up within this range is killed right away (they spawn at the hook, so keep it past your cast distance)")
    private val blastRange by SliderSetting("Hyperion Reach", 6.0, 3.0, 10.0, 0.5, " blocks").withDescription("Casts once a creature is this close (the blast hits around you)")
    private val weaponSlot by SliderSetting("Weapon Slot", 0, 0, 9, 1).withDescription("Hotbar slot (1-9) of the weapon. 0 = Hyperion / Astraea / Scylla / Valkyrie if found")
    private val castDelay by SliderSetting("Hit Delay", 4, 1, 20, 1, " ticks")
    private val stopHealth by SliderSetting("Stop Below Health", 30, 0, 90, 5, "%").section("Safety").withDescription("Stops when your health drops below this (0 = never)")
    private val chatInfo by ToggleSetting("Chat Messages", true)
    private val statusHud by ToggleSetting("Status HUD", true)

    private enum class State { CAST, WAIT_BITE, REEL, FIGHT }

    private val WITHER_BLADES = setOf("HYPERION", "ASTRAEA", "SCYLLA", "VALKYRIE")
    private val NPC_CLASSES = setOf("Villager", "WanderingTrader")

    private var running = false
    private var state = State.CAST
    private var wait = 0
    private var timer = 0
    private var inFluidTicks = 0
    private var hitCooldown = 0
    private var spotYaw = 0f
    private var spotPitch = 0f
    private var caught = 0
    private var kills = 0
    private var status = "idle"
    private var lastMobIds = emptySet<Int>()
    /** Creature id -> ticks it has been out of reach; given up on after 15 s so fishing goes on. */
    private val waiting = HashMap<Int, Int>()
    private val ignored = HashSet<Int>()

    override fun init() {
        register<TickEvent.Start> {
            if (toggleKey.isPressed() && mc.gui.screen() == null) {
                if (running) stop("stopped") else start()
            }
            if (running) runCatching(::tick).onFailure { stop("error: ${it.message}") }
        }
        register<WorldChangeEvent> { if (running) stop("world changed") }

        hudElement("Auto Fishing Status", { statusHud.value }, { running }) { ctx, example ->
            val lines = if (example) listOf("§dAuto Fishing §aWAIT_BITE", "§7caught §f42  §7kills §f7")
            else listOf("§dAuto Fishing §a$state §8($status)", "§7caught §f$caught  §7kills §f$kills")
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
        spotYaw = p.yRot
        spotPitch = p.xRot
        running = true
        state = State.CAST
        wait = 0
        caught = 0
        kills = 0
        waiting.clear()
        ignored.clear()
        info("&aAuto Fishing started. Press the key again to stop.")
    }

    private fun stop(reason: String?) {
        running = false
        mc.options.keyUse.isDown = false
        mc.options.keyAttack.isDown = false
        if (reason != null) info("&eAuto Fishing $reason &7(caught $caught, killed $kills).")
    }

    /** Hotbar index of the rod. */
    private fun rod(): Int? {
        if (rodSlot.value in 1 .. 9) return rodSlot.value - 1
        return PlayerUtils.findHotbarSlot { it.item == Items.FISHING_ROD }
    }

    private fun hold(slot: Int) {
        val p = mc.player ?: return
        if (p.inventory.selectedSlot != slot) p.inventory.selectedSlot = slot
    }

    private fun tick() {
        val p = mc.player ?: return stop("no player")
        if (mc.gui.screen() != null) return
        if (stopHealth.value > 0 && p.health / p.maxHealth * 100f < stopHealth.value) return stop("health is low")
        if (hitCooldown > 0) hitCooldown--

        // sea creatures first
        val mobs = if (killCreatures.value) creatures(p) else emptyList()
        if (state == State.FIGHT) kills += lastMobIds.count { id -> p.level().getEntity(id)?.isAlive != true }
        lastMobIds = mobs.map { it.id }.toSet()
        if (state == State.FIGHT || mobs.isNotEmpty()) {
            if (mobs.isEmpty()) {
                state = State.CAST
                wait = recastDelay.value
                PlayerUtils.rotate(spotYaw, spotPitch)
                status = "back to fishing"
                return
            }
            if (state != State.FIGHT) {
                mc.options.keyUse.isDown = false
                if (p.fishing != null) rod()?.let { hold(it); PlayerUtils.rightClick() } // pull the line in first
            }
            state = State.FIGHT
            fight(p, mobs)
            return
        }

        if (wait > 0) {
            wait--
            return
        }
        val rod = rod() ?: return stop("no fishing rod in the hotbar")
        val hook = p.fishing

        when (state) {
            State.CAST -> {
                hold(rod)
                PlayerUtils.rotate(spotYaw, spotPitch)
                if (hook != null) { // a line is still out: pull it in first
                    PlayerUtils.rightClick()
                    wait = recastDelay.value
                    return
                }
                PlayerUtils.rightClick()
                state = State.WAIT_BITE
                timer = 0
                inFluidTicks = 0
                status = "cast"
                wait = 3
            }

            State.WAIT_BITE -> {
                if (hook == null) { // line broke / got reeled by something else
                    state = State.CAST
                    wait = recastDelay.value
                    return
                }
                timer++
                if (hook.isInWater || hook.isInLava) inFluidTicks++
                status = "waiting ${timer / 20}s"
                if (bitten(hook)) {
                    state = State.REEL
                    val lo = minOf(reelMin.value, reelMax.value)
                    val hi = maxOf(reelMin.value, reelMax.value)
                    wait = if (hi > lo) kotlin.random.Random.nextInt(lo, hi + 1) else lo
                    status = "bite!"
                }
                else if (timer > castTimeout.value * 20) {
                    hold(rod)
                    PlayerUtils.rightClick()
                    state = State.CAST
                    wait = recastDelay.value
                    status = "nothing bit, recasting"
                }
            }

            State.REEL -> {
                hold(rod)
                PlayerUtils.rightClick()
                caught++
                state = State.CAST
                wait = recastDelay.value
                status = "reeled in"
            }

            State.FIGHT -> {}
        }
    }

    /** SkyBlock puts a "!!!" stand over the hook on a bite; vanilla pulls the hook down. */
    private fun bitten(hook: FishingHook): Boolean {
        val level = hook.level()
        val sign = level.getEntitiesOfClass(ArmorStand::class.java, hook.boundingBox.inflate(1.5, 3.0, 1.5)) {
            it.customName?.string?.contains("!!!") == true
        }
        if (sign.isNotEmpty()) return true
        return hookMotion.value && inFluidTicks > 10 && hook.deltaMovement.y < - 0.05
    }

    /** Living mobs close by (not players, NPCs or name stands). */
    private fun creatures(p: Player): List<LivingEntity> {
        val r = killRange.value
        return p.level().getEntitiesOfClass(Mob::class.java, p.boundingBox.inflate(r, 4.0, r)) { e ->
            e.isAlive && e.health > 0f && e.id !in ignored && e.javaClass.simpleName !in NPC_CLASSES && e.distanceTo(p) <= r
        }
    }

    private fun fight(p: Player, mobs: List<LivingEntity>) {
        val hype = if (weaponSlot.value in 1 .. 9) null else PlayerUtils.findHotbarSlot { it.skyblockId in WITHER_BLADES }
        // a creature that never comes within reach (stuck in the water) is left alone after 15 s
        val reach = if (hype != null) blastRange.value else 3.2
        for (m in mobs) {
            if (p.distanceTo(m) <= reach) waiting.remove(m.id)
            else if ((waiting.merge(m.id, 1, Int::plus) ?: 0) > 300) {
                ignored += m.id
                waiting.remove(m.id)
            }
        }
        val close = mobs.filter { p.distanceTo(it) <= reach }
        if (close.isEmpty()) {
            val far = mobs.minByOrNull { it.distanceToSqr(p) } ?: return
            status = "waiting for ${far.name.string} to come closer"
            val rot = MathUtils.calcYawPitch(far.boundingBox.center)
            PlayerUtils.rotate(rot.yaw, rot.pitch)
            return
        }
        if (hype != null) {
            hold(hype)
            status = "Hyperion on ${close.size}"
            PlayerUtils.rotate(p.yRot, 90f) // look straight down: the blast covers everything around us
            if (hitCooldown > 0) return
            hitCooldown = castDelay.value
            mc.gameMode?.useItem(p, InteractionHand.MAIN_HAND)
            p.swing(InteractionHand.MAIN_HAND)
            return
        }
        if (weaponSlot.value in 1 .. 9) hold(weaponSlot.value - 1)
        val target = close.minByOrNull { it.distanceToSqr(p) } ?: return
        status = "fighting ${target.name.string}"
        val rot = MathUtils.calcYawPitch(target.boundingBox.center)
        PlayerUtils.rotate(rot.yaw, rot.pitch)
        if (hitCooldown > 0 || p.distanceTo(target) > 3.2f) return // don't leave the fishing spot
        hitCooldown = castDelay.value
        mc.gameMode?.attack(p, target)
        p.swing(InteractionHand.MAIN_HAND)
    }
}
//#endif
