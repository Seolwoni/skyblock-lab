package com.github.noamm9.features.impl.routes

//#if CHEAT
import com.github.noamm9.config.types.KeybindSetting
import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.RenderWorldEvent
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.utils.ChatUtils.modMessage
import com.github.noamm9.utils.MathUtils.aabb
import com.github.noamm9.utils.PlayerUtils
import com.github.noamm9.utils.dungeons.F7Listener
import com.github.noamm9.utils.dungeons.enums.TerminalSection
import com.github.noamm9.utils.dungeons.enums.WitherRelic
import com.github.noamm9.utils.location.LocationUtils
import com.github.noamm9.utils.render.Render2D.drawString
import com.github.noamm9.utils.render.world.Render3D.renderBlock
import com.github.noamm9.utils.render.world.Render3D.renderLine
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.awt.Color

/**
 * F7 / M7 boss helpers that move for you:
 * P3 - walks to every inactive terminal and lever of the current section and opens / flips them
 * (Auto Terminal solves the terminal), then follows the run into the next section.
 * P5 - picks up your relic and carries it to its cauldron.
 */
object AutoBoss: Feature("F7/M7 boss: does the terminals and levers of each P3 section by itself (with Auto Terminal) and carries relics to their cauldrons in P5.") {
    private val toggleKey by KeybindSetting("Start / Stop Key").withDescription("Press in the F7 / M7 boss to start, press again to stop")
    private val autoP3 by ToggleSetting("P3 Terminals", true).section("Phase 3").withDescription("Goes to the closest inactive terminal of the section, opens it and lets Auto Terminal solve it")
    private val autoLevers by ToggleSetting("P3 Levers", true).withDescription("Flips the section's two levers")
    private val followSections by ToggleSetting("Follow Sections", true).withDescription("When a section is done, heads into the next one")
    private val reach by SliderSetting("Interact Range", 2.8, 2.0, 4.5, 0.1, " blocks")
    private val autoRelics by ToggleSetting("P5 Relics", true).section("Phase 5").withDescription("Picks up the relic near you and puts it in its cauldron")
    private val showTarget by ToggleSetting("Show Target", true).section("Debug")
    private val chatInfo by ToggleSetting("Chat Messages", true)

    private var running = false
    private var status = "idle"
    private var targetPos: Vec3? = null
    private var cooldown = com.github.noamm9.utils.Humanize.around(0)
    private var stuck = 0
    private var leverCache: Pair<Int, List<BlockPos>>? = null
    private val tried = HashMap<BlockPos, Int>()

    /** Same boxes as LocationUtils.findP3Section. */
    private val sections = arrayOf(
        aabb(90, 158, 123, 111, 105, 32),
        aabb(16, 158, 122, 111, 105, 143),
        aabb(19, 158, 48, - 3, 106, 142),
        aabb(91, 158, 50, - 3, 106, 30),
    )

    override fun init() {
        register<TickEvent.Start> {
            if (toggleKey.isPressed() && mc.gui.screen() == null) {
                if (running) stop("stopped") else start()
            }
            if (running) runCatching(::tick).onFailure { com.github.noamm9.utils.Safely.report("AutoBoss tick", it); stop("error: ${it.message}") }
        }
        register<WorldChangeEvent> { if (running) stop("world changed") }

        register<RenderWorldEvent> {
            if (! running || ! showTarget.value) return@register
            val t = targetPos ?: return@register
            val p = mc.player ?: return@register
            event.ctx.renderLine(p.position().add(0.0, 0.1, 0.0), t, Color(236, 72, 153), 2, phase = true)
            event.ctx.renderBlock(BlockPos.containing(t), Color(167, 107, 250), fill = false, phase = true, lineWidth = 3)
        }

        hudElement("Auto Boss Status", { true }, { running }) { ctx, example ->
            val lines = if (example) listOf("§dAuto Boss §aP3 S2", "§7terminal 3/5") else listOf("§dAuto Boss §aP${LocationUtils.F7Phase ?: "-"} ${F7Listener.section}", "§7$status")
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
        if (LocationUtils.dungeonFloorNumber != 7 || ! LocationUtils.inBoss) return info("&cStart this in the F7 / M7 boss.")
        running = true
        tried.clear()
        leverCache = null
        info("&aAuto Boss started.")
    }

    private fun stop(reason: String?) {
        running = false
        targetPos = null
        AutoRoutes.releaseAll()
        if (reason != null) info("&eAuto Boss $reason.")
    }

    private fun tick() {
        if (mc.gui.screen() != null) { // a terminal is open: Auto Terminal is on it
            AutoRoutes.releaseAll()
            cooldown = com.github.noamm9.utils.Humanize.around(6)
            status = "in a terminal"
            return
        }
        if (cooldown > 0) { cooldown--; return }
        when (LocationUtils.F7Phase) {
            3 -> phase3()
            5 -> phase5()
            else -> { status = "waiting (P${LocationUtils.F7Phase ?: "-"})"; targetPos = null; AutoRoutes.releaseAll() }
        }
    }

    // ------------------------------------------------------------------ P3

    private fun currentSection(): Int? = when (F7Listener.section) {
        TerminalSection.S1 -> 1
        TerminalSection.S2 -> 2
        TerminalSection.S3 -> 3
        TerminalSection.S4 -> 4
        else -> null
    }

    private fun phase3() {
        if (F7Listener.section == TerminalSection.GOLDOR) return stop("P3 done, the core is open")
        val sec = currentSection() ?: LocationUtils.P3Section ?: return run { status = "waiting for Goldor" }
        val box = sections[sec - 1]
        val p = mc.player!!

        val terminals = if (autoP3.value) p.level().getEntitiesOfClass(ArmorStand::class.java, box) {
            it.customName?.string == "Inactive Terminal" && (tried[it.blockPosition()] ?: 0) < 4
        } else emptyList()
        val levers = if (autoLevers.value) leversIn(sec, box).filter { val st = p.level().getBlockState(it); st.block is LeverBlock && ! st.getValue(LeverBlock.POWERED) && (tried[it] ?: 0) < 4 } else emptyList()

        data class Task(val pos: Vec3, val key: BlockPos, val lever: Boolean)
        val tasks = terminals.map { Task(it.position().add(0.0, 1.3, 0.0), it.blockPosition(), false) } +
            levers.map { Task(Vec3.atCenterOf(it), it, true) }

        val task = tasks.minByOrNull { it.pos.distanceToSqr(p.eyePosition) }
        if (task == null) {
            targetPos = null
            if (! followSections.value) return run { status = "section $sec done"; AutoRoutes.releaseAll() }
            // section finished on our side: walk into the next one so its tasks load
            val next = sections.getOrNull(sec % 4) ?: return
            val centre = Vec3((next.minX + next.maxX) / 2, 115.0, (next.minZ + next.maxZ) / 2)
            status = "to section ${sec % 4 + 1}"
            if (LocationUtils.P3Section != sec % 4 + 1) AutoRoutes.travelTick(centre, 6.0) else AutoRoutes.releaseAll()
            return
        }

        targetPos = task.pos
        status = if (task.lever) "lever at ${task.key.x}, ${task.key.y}, ${task.key.z}" else "terminal at ${task.key.x}, ${task.key.y}, ${task.key.z}"
        if (p.eyePosition.distanceTo(task.pos) > reach.value) {
            if (AutoRoutes.travelTick(task.pos, reach.value - 0.6) == AutoRoutes.Travel.STUCK && ++stuck > 6) {
                tried[task.key] = 99 // unreachable from here, leave it to the team
                stuck = 0
            }
            return
        }
        stuck = 0
        if (AutoRoutes.lookAndUse(task.pos)) {
            tried[task.key] = (tried[task.key] ?: 0) + 1
            cooldown = com.github.noamm9.utils.Humanize.around(if (task.lever) 6 else 10)
        }
    }

    /** The section's task levers: lone levers (the lights device's lever grid is left alone). Cached per section. */
    private fun leversIn(sec: Int, box: AABB): List<BlockPos> {
        leverCache?.takeIf { it.first == sec }?.let { return it.second }
        val level = mc.level ?: return emptyList()
        val found = ArrayList<BlockPos>()
        for (x in box.minX.toInt() .. box.maxX.toInt()) for (y in box.minY.toInt() .. box.maxY.toInt()) for (z in box.minZ.toInt() .. box.maxZ.toInt()) {
            val pos = BlockPos(x, y, z)
            if (level.getBlockState(pos).block is LeverBlock) found += pos
        }
        val lone = found.filter { a -> found.none { b -> b != a && b.distManhattan(a) <= 2 } }
        leverCache = sec to lone
        return lone
    }

    // ------------------------------------------------------------------ P5

    private fun phase5() {
        if (! autoRelics.value) return run { status = "P5 (relics off)" }
        val p = mc.player!!
        val held = WitherRelic.fromName(p.inventory.getItem(8).hoverName.string)
        if (held != null) {
            val cauldron = held.cauldronPos
            targetPos = cauldron
            status = "placing ${held.coloredName}"
            if (p.eyePosition.distanceTo(cauldron) > 4.0) {
                AutoRoutes.travelTick(cauldron, 3.0)
                return
            }
            PlayerUtils.swapToSlot(8)
            if (AutoRoutes.lookAndUse(cauldron)) cooldown = com.github.noamm9.utils.Humanize.around(8)
            return
        }
        val relic = p.level().getEntitiesOfClass(ArmorStand::class.java, p.boundingBox.inflate(40.0, 15.0, 40.0)) {
            it.getItemBySlot(EquipmentSlot.HEAD).hoverName.string.contains("Relic")
        }.minByOrNull { it.distanceToSqr(p) }
        if (relic == null) {
            targetPos = null
            status = "no relic nearby"
            AutoRoutes.releaseAll()
            return
        }
        val head = relic.position().add(0.0, 1.5, 0.0)
        targetPos = head
        status = "picking up a relic"
        if (p.eyePosition.distanceTo(head) > reach.value) {
            AutoRoutes.travelTick(relic.position(), reach.value - 0.8)
            return
        }
        if (AutoRoutes.lookAndUse(head)) cooldown = com.github.noamm9.utils.Humanize.around(6)
    }
}
//#endif
