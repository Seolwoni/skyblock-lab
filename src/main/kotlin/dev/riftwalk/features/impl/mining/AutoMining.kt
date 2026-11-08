package dev.riftwalk.features.impl.mining

//#if CHEAT
import dev.riftwalk.config.types.DropdownSetting
import dev.riftwalk.config.types.KeybindSetting
import dev.riftwalk.config.types.MultiCheckboxSetting
import dev.riftwalk.config.types.SliderSetting
import dev.riftwalk.config.types.TextInputSetting
import dev.riftwalk.config.types.ToggleSetting
import dev.riftwalk.event.impl.ChatMessageEvent
import dev.riftwalk.event.impl.MainThreadPacketReceivedEvent
import dev.riftwalk.event.impl.TickEvent
import dev.riftwalk.event.impl.WorldChangeEvent
import dev.riftwalk.features.Feature
import dev.riftwalk.ui.notification.NotificationManager
import dev.riftwalk.utils.ChatUtils
import dev.riftwalk.utils.HumanProfile
import dev.riftwalk.utils.HumanTiming
import dev.riftwalk.utils.Humanize
import dev.riftwalk.utils.Journal
import dev.riftwalk.utils.MathUtils
import dev.riftwalk.utils.PlayerUtils
import dev.riftwalk.utils.WatchSigns
import dev.riftwalk.utils.items.ItemUtils.skyblockId
import dev.riftwalk.utils.render.Render2D.drawString
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.Random

/**
 * Mines by itself where you stand. Mithril (and Titanium) in the Dwarven Mines, or gemstones.
 * Picks the next block like a person would (the wanted kind first, then a short turn), turns with
 * Human Behaviour, holds the pickaxe on it, waits a human moment after it breaks and goes on.
 * The pure parts (block kinds, choosing, where to aim) are in [MiningMath].
 */
object AutoMining: Feature("Mines by itself where you stand: Mithril and Titanium, or gemstones. Picks blocks like a person, turns with Human Behaviour, uses the pickaxe ability.") {
    private val toggleKey by KeybindSetting("Start / Stop Key").withDescription("Stand by the blocks and press it to start, press again to stop")
    private val mode by DropdownSetting("Mode", 0, listOf("Mithril", "Gemstone")).withDescription("Mithril: Dwarven Mines mithril and titanium. Gemstone: the gem colours ticked below")

    private val titanium by ToggleSetting("Titanium", true).section("Mithril").showIf { mode.value == 0 }
    private val blue by ToggleSetting("Blue Mithril", true).showIf { mode.value == 0 }.withDescription("Light blue wool, worth the most mithril")
    private val green by ToggleSetting("Green Mithril", true).showIf { mode.value == 0 }.withDescription("Prismarine")
    private val grey by ToggleSetting("Grey Mithril", true).showIf { mode.value == 0 }.withDescription("Grey wool")

    private val gems by MultiCheckboxSetting("Gemstones", MiningMath.GEMSTONES.associate { it.name to true }.toMutableMap()).section("Gemstone").showIf { mode.value == 1 }

    private val reach by SliderSetting("Reach", 4.5, 3.0, 5.5, 0.1, " blocks").section("Mining").withDescription("How far away a block may be (from your eyes)")
    private val switchMin by SliderSetting("Switch Delay Min", 1, 0, 20, 1, " ticks").withDescription("Shortest wait after a block breaks before going for the next")
    private val switchMax by SliderSetting("Switch Delay Max", 14, 1, 40, 1, " ticks").withDescription("Longest wait (most waits are short, a few are long, like a person)")
    private val giveUp by SliderSetting("Give Up After", 6, 2, 30, 1, " s").withDescription("A block that hasn't broken by then is skipped for 20 s. Grows to 3x the usual break time of that block kind once it has been seen (slow titanium isn't given up too early)")
    private val leaveContested by ToggleSetting("Leave Blocks Others Mine", true).withDescription("Another player looking at our block from close by: take a different one")
    private val stallSeconds by SliderSetting("Stall Recovery After", 10, 0, 120, 5, " s").withDescription("Nothing has changed for this long (no block broken, same target, same spot) while there were blocks: writes down why in the log and starts over. 3 in 2 minutes stops. 0 = off")
    private val whenFull by DropdownSetting("When Inventory Full", 0, listOf("Stop", "Keep Going")).withDescription("Stop: stop with a report once no slot is free. Keep Going: for when everything goes to sacks")
    private val ability by ToggleSetting("Use Pickaxe Ability", true).withDescription("Right-clicks the pickaxe when its ability (Mining Speed Boost, Pickobulus...) says it's available")

    private val failsafe by ToggleSetting("Stop On Anything Unexpected", true).section("Failsafe")
    private val stopOnParty by ToggleSetting("Stop On Party Invite", false).showIf { failsafe.value }
    private val stopOnMessage by ToggleSetting("Stop On Private Message", true).showIf { failsafe.value }
    private val stopOnStaff by ToggleSetting("Stop On Staff Nearby", true).showIf { failsafe.value }
    private val staffTags by TextInputSetting("Staff Tags", "ADMIN, OWNER, MOD, HELPER, GM, YOUTUBE").showIf { failsafe.value && stopOnStaff.value }
    private val staffRange by SliderSetting("Staff Range", 48, 8, 128, 4, " blocks").showIf { failsafe.value && stopOnStaff.value }
    private val stopOnMoved by ToggleSetting("Stop When Moved Or Turned", true).showIf { failsafe.value }.withDescription("The server teleports or turns you")
    private val stopOnDeath by ToggleSetting("Stop On Death", true).showIf { failsafe.value }

    private val chatInfo by ToggleSetting("Chat Messages", true).section("Display")
    private val statusHud by ToggleSetting("Status HUD", true)

    private val journal = Journal("mining")
    private val rnd = Random()

    private var running = false
    private var profile = HumanProfile()
    private var timing = HumanTiming()
    private var tick = 0
    private var target: BlockPos? = null
    private var targetKind: MiningMath.Kind? = null
    private var targetSince = 0
    private var aimU = 0.0
    private var aimV = 0.0
    private var wait = 0
    private var idleSince = - 1
    private val skipped = HashMap<Long, Int>()
    private val broken = LinkedHashMap<String, Int>()
    private var startMs = 0L
    private var abilityReady = true
    private var abilityTriedAt = - 10_000
    private var status = ""

    // robustness bookkeeping
    private val breakTicks = HashMap<String, ArrayDeque<Int>>()   // kind -> recent break times (ticks)
    private var stallSig = ""
    private var stallSince = 0
    private val stalls = ArrayDeque<Int>()
    private var statStalls = 0
    private var statSkips = 0
    private var statContested = 0
    private var fullSince = - 1

    override fun init() {
        register<TickEvent.Start> {
            if (toggleKey.isPressed() && mc.gui.screen() == null) {
                if (running) stop("stopped") else start()
            }
            if (running) runCatching(::step).onFailure { dev.riftwalk.utils.Safely.report("AutoMining tick", it); stop("error: ${it.message}") }
        }
        register<ChatMessageEvent> {
            if (! running) return@register
            val t = event.unformattedText.replace(Regex("§."), "").trim()
            if (whenFull.value == 0 && t.contains("inventory is full", ignoreCase = true) && ! Regex("^(?:\\[[^]]+] )*[A-Za-z0-9_]{2,16}: ").containsMatchIn(t)) return@register stop("the inventory is full")
            if (failsafe.value) {
                if (stopOnParty.value) WatchSigns.partyInvite(t)?.let { return@register failStop("party invite from $it") }
                if (stopOnMessage.value) WatchSigns.privateMessage(t)?.let { return@register failStop("private message from $it") }
                if (stopOnDeath.value && t.startsWith("☠ You")) return@register failStop("died ($t)")
            }
            when {
                t.endsWith("is now available!") -> { abilityReady = true; journal.log("ability ready: $t") }
                t.startsWith("You used your") && t.contains("Pickaxe Ability") -> { abilityReady = false; journal.log("ability used: $t") }
                t.contains("pickaxe ability is on cooldown", ignoreCase = true) -> abilityReady = false
            }
        }
        register<WorldChangeEvent> { if (running) stop("world changed") }
        register<MainThreadPacketReceivedEvent.Pre> {
            if (! running || ! failsafe.value || ! stopOnMoved.value) return@register
            val name = event.packet.javaClass.simpleName
            if (event.packet is ClientboundPlayerPositionPacket || name.contains("PlayerRotation")) mc.execute { if (running) failStop("the server moved or turned you") }
        }
        hudElement("Auto Mining Status", { statusHud.value }, { running }) { ctx, example ->
            val lines = if (example) listOf("§dAuto Mining §7- mining Blue Mithril", "§7Blocks §f412 §8(1,830/h)") else hudLines()
            lines.forEachIndexed { i, l -> ctx.drawString(l, 0, i * 10) }
            (lines.maxOfOrNull { mc.font.width(it) } ?: 0) to lines.size * 10
        }
    }

    override fun onDisable() {
        super.onDisable()
        if (running) stop(null)
    }

    // ------------------------------------------------------------------ start / stop

    private fun start() {
        val p = mc.player ?: return
        if (! selectTool()) return info("&cNo pickaxe, drill or gauntlet in the hotbar.")
        if (kinds().isEmpty()) return info("&cNothing to mine: tick at least one block kind in the settings.")
        profile = Humanize.profile()
        timing = HumanTiming().apply(profile)
        tick = 0; target = null; targetKind = null; wait = 0; idleSince = - 1
        skipped.clear(); broken.clear(); breakTicks.clear()
        stallSig = ""; stallSince = 0; stalls.clear(); statStalls = 0; statSkips = 0; statContested = 0; fullSince = - 1
        startMs = System.currentTimeMillis()
        abilityReady = true; abilityTriedAt = - 10_000
        running = true
        journal.log("==== start  mode=${listOf("Mithril", "Gemstone")[mode.value]}  kinds=${kinds().joinToString { it.name }}  at ${p.blockPosition().toShortString()}  profile=$profile")
        info("&aAuto Mining started. Press the key again to stop.")
    }

    private fun failStop(why: String) {
        journal.log("FAILSAFE  $why")
        stop("stopped: $why")
        NotificationManager.push("Auto Mining stopped", why)
        dev.riftwalk.utils.SoundUtils.playSound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value(), 1f, 0.6f)
    }

    private fun stop(reason: String?) {
        if (running) report(reason ?: "stopped")
        running = false
        target = null
        mc.options.keyAttack.isDown = false
        if (reason != null) info("&eAuto Mining $reason.")
    }

    private fun report(reason: String) {
        val ms = System.currentTimeMillis() - startMs
        val total = broken.values.sum()
        val lines = buildList {
            add("&7Time &f${ms / 60_000}m ${(ms / 1000) % 60}s")
            add("&7Blocks &f$total &8(${"%,d".format(MiningMath.perHour(total, ms))}/h)")
            if (broken.isNotEmpty()) add("&7By kind &f" + broken.entries.joinToString(", ") { "${it.key} ${it.value} (${MiningMath.median(breakTicks[it.key]?.toList().orEmpty())}t)" })
            add("&7Problems &fskipped $statSkips, left to others $statContested, stalls $statStalls")
            add("&7Ended &f$reason")
        }
        Journal("report").log("Auto Mining  " + lines.joinToString("  |  ") { it.replace(Regex("&."), "") })
        if (chatInfo.value && ms > 10_000) ChatUtils.modBlock("&dMining report", lines)
    }

    private fun info(msg: String) {
        journal.log("info  $msg")
        if (chatInfo.value) ChatUtils.modMessage(msg)
    }

    // ------------------------------------------------------------------ the tick

    private fun step() {
        val p = mc.player ?: return
        val level = mc.level ?: return
        tick++
        if (mc.gui.screen() != null) { release(); status = "paused (menu open)"; return }
        if (failsafe.value && stopOnStaff.value && tick % 20 == 0) staffNearby()?.let { return failStop("staff nearby: $it") }
        if (! holdingTool() && ! selectTool()) return failStop("the pickaxe is gone from the hotbar")

        skipped.entries.removeIf { it.value <= tick }
        if (whenFull.value == 0 && tick % 20 == 0) {
            val free = (0 until 36).count { p.inventory.getItem(it).isEmpty }
            if (free > 0) fullSince = - 1 else if (fullSince < 0) fullSince = tick else if (tick - fullSince > 60) return stop("the inventory is full")
        }
        if (watchdog()) return
        if (wait > 0) { wait--; release(); return }

        // the block we were on broke (it changed into something we don't mine)
        target?.let { t ->
            if (kindOf(BuiltInRegistries.BLOCK.getKey(level.getBlockState(t).block).path) == null) {
                val k = targetKind?.name ?: "?"
                broken[k] = (broken[k] ?: 0) + 1
                breakTicks.getOrPut(k) { ArrayDeque() }.apply { addLast(tick - targetSince); while (size > 30) removeFirst() }
                journal.log("broke $k at ${t.toShortString()} in ${tick - targetSince} ticks")
                target = null
                release()
                wait = timing.next(switchMin.value, maxOf(switchMin.value, switchMax.value))
                return
            }
            val limit = MiningMath.giveUpTicks(breakTicks[targetKind?.name]?.toList().orEmpty(), giveUp.value * 20, giveUp.value * 80)
            if (tick - targetSince > limit) {
                journal.log("gave up on ${targetKind?.name} at ${t.toShortString()} after ${(tick - targetSince) / 20.0} s (limit ${limit / 20.0} s)")
                skipped[t.asLong()] = tick + 400
                statSkips++
                target = null
            } else if (leaveContested.value && tick % 10 == 0 && contested(t) != null) {
                journal.log("left ${t.toShortString()} to ${contested(t)}")
                skipped[t.asLong()] = tick + 200
                statContested++
                target = null
            } else if (! visible(t)) {
                journal.log("lost sight of ${t.toShortString()}")
                target = null
            }
        }

        if (target == null) {
            val next = pick()
            if (next == null) {
                release()
                if (idleSince < 0) { idleSince = tick; journal.log("nothing in reach, waiting") }
                status = "waiting for blocks"
                return
            }
            if (idleSince >= 0) journal.log("blocks back after ${(tick - idleSince) / 20} s")
            idleSince = - 1
            target = next.first; targetKind = next.second; targetSince = tick
            aimU = rnd.nextGaussian() * 0.45; aimV = rnd.nextGaussian() * 0.45
            wait = Humanize.reaction().coerceAtMost(6)
            if (wait > 0) return
        }

        val t = target ?: return
        val aim = aimPoint(t)
        val rot = MathUtils.calcYawPitch(aim)
        Humanize.turnTo(p, rot.yaw, rot.pitch, tolerance = 2f)
        val hit = mc.hitResult as? BlockHitResult
        val onTarget = hit != null && hit.type == HitResult.Type.BLOCK && hit.blockPos == t
        mc.options.keyAttack.isDown = onTarget
        status = "mining ${targetKind?.name}"

        if (onTarget && ability.value && abilityReady && tick - abilityTriedAt > 1200) {
            abilityTriedAt = tick
            PlayerUtils.rightClick()
            journal.log("ability: right-click")
        }
    }

    /**
     * Stall watchdog: the same target, the same block count and the same spot for Stall Recovery After seconds,
     * while there were blocks to mine, means something is stuck. Log what it looked like and start over.
     * True when it acted this tick.
     */
    private fun watchdog(): Boolean {
        if (stallSeconds.value <= 0 || idleSince >= 0) { stallSince = tick; return false }
        val p = mc.player ?: return false
        val sig = "${target?.toShortString()}|${broken.values.sum()}|${p.blockPosition().toShortString()}"
        if (sig != stallSig) { stallSig = sig; stallSince = tick; return false }
        // a slow block that is still within its give-up time is not a stall
        val limit = if (target != null) MiningMath.giveUpTicks(breakTicks[targetKind?.name]?.toList().orEmpty(), giveUp.value * 20, giveUp.value * 80) + 40 else 0
        if (tick - stallSince < maxOf(stallSeconds.value * 20, limit)) return false
        val hit = mc.hitResult
        journal.log("STALL  ${stallSeconds.value} s without change: target=${target?.toShortString()} kind=${targetKind?.name} " +
            "attack=${mc.options.keyAttack.isDown} wait=$wait hit=${(hit as? BlockHitResult)?.blockPos?.toShortString() ?: hit?.type} " +
            "held=${p.mainHandItem.hoverName.string} yaw=${"%.1f".format(p.yRot)} pitch=${"%.1f".format(p.xRot)} status=$status")
        statStalls++
        stalls.addLast(tick)
        while (stalls.isNotEmpty() && tick - stalls.first() > 2400) stalls.removeFirst()
        if (stalls.size >= 3) { failStop("stuck 3 times in 2 minutes"); return true }
        target?.let { skipped[it.asLong()] = tick + 400 }
        target = null
        wait = Humanize.reaction()
        release()
        selectTool()
        stallSince = tick
        return true
    }

    /** "name" of another player close by who is looking at [pos], or null. */
    private fun contested(pos: BlockPos): String? {
        val p = mc.player ?: return null
        val c = Vec3.atCenterOf(pos)
        for (o in p.level().players()) {
            if (o === p || o.distanceToSqr(c) > 36.0) continue
            val e = o.eyePosition
            val l = o.lookAngle
            if (MiningMath.onRay(e.x, e.y, e.z, l.x, l.y, l.z, c.x, c.y, c.z, 5.5, 0.75)) return o.name.string
        }
        return null
    }

    private fun release() {
        if (mc.options.keyAttack.isDown) mc.options.keyAttack.isDown = false
    }

    // ------------------------------------------------------------------ blocks

    private fun kinds(): List<MiningMath.Kind> = when (mode.value) {
        0 -> listOfNotNull(
            MiningMath.TITANIUM.takeIf { titanium.value }, MiningMath.MITHRIL_BLUE.takeIf { blue.value },
            MiningMath.MITHRIL_GREEN.takeIf { green.value }, MiningMath.MITHRIL_GREY.takeIf { grey.value },
        )
        else -> MiningMath.GEMSTONES.filter { gems.value[it.name] == true }
    }

    private fun kindOf(id: String): MiningMath.Kind? = kinds().firstOrNull { id in it.ids }

    private fun eye(): Vec3 = mc.player !!.eyePosition

    /** Where on the block we aim: a spot on the face we see (panes: near the middle, they're thin). */
    private fun aimPoint(pos: BlockPos): Vec3 {
        val e = eye()
        val id = BuiltInRegistries.BLOCK.getKey(mc.level !!.getBlockState(pos).block).path
        if (id.endsWith("_pane")) return Vec3(pos.x + 0.5, pos.y + 0.5 + aimV * 0.15, pos.z + 0.5)
        val f = MiningMath.facePoint(e.x, e.y, e.z, pos.x, pos.y, pos.z, aimU, aimV)
        return Vec3(f[0], f[1], f[2])
    }

    /** True when nothing stands between the eye and the spot we'd aim at on [pos]. */
    private fun visible(pos: BlockPos): Boolean {
        val e = eye()
        val a = aimPoint(pos)
        if (e.distanceTo(a) > reach.value + 0.3) return false
        val hit = mc.level !!.clip(ClipContext(e, a.add(a.subtract(e).normalize().scale(0.05)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player !!))
        return hit.type == HitResult.Type.BLOCK && hit.blockPos == pos
    }

    private fun pick(): Pair<BlockPos, MiningMath.Kind>? {
        val p = mc.player ?: return null
        val level = mc.level ?: return null
        val e = eye()
        val r = kotlin.math.ceil(reach.value).toInt() + 1
        val base = BlockPos.containing(e)
        val found = HashMap<Long, Pair<BlockPos, MiningMath.Kind>>()
        val cands = ArrayList<MiningMath.Candidate>()
        for (dx in - r .. r) for (dy in - r .. r) for (dz in - r .. r) {
            val pos = base.offset(dx, dy, dz)
            if (skipped.containsKey(pos.asLong())) continue
            val kind = kindOf(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).block).path) ?: continue
            val c = Vec3.atCenterOf(pos)
            val dist = e.distanceTo(c)
            if (dist > reach.value + 0.6 || ! visible(pos)) continue
            if (leaveContested.value && contested(pos) != null) continue
            found[pos.asLong()] = pos.immutable() to kind
            cands += MiningMath.Candidate(pos.asLong(), dist, MiningMath.turnDegrees(p.yRot, p.xRot, c.x - e.x, c.y - e.y, c.z - e.z), kind.priority)
        }
        val choice = MiningMath.choose(cands, profile, rnd) ?: return null
        return found[choice.key]
    }

    // ------------------------------------------------------------------ tool

    private fun isTool(s: ItemStack?): Boolean {
        if (s == null || s.isEmpty) return false
        val id = s.skyblockId
        return id.contains("PICKAXE") || id.contains("DRILL") || id.contains("GAUNTLET") || s.item.toString().contains("pickaxe")
    }

    private fun holdingTool() = isTool(mc.player?.mainHandItem)

    private fun selectTool(): Boolean {
        val p = mc.player ?: return false
        if (holdingTool()) return true
        val slot = (0 .. 8).firstOrNull { isTool(p.inventory.getItem(it)) } ?: return false
        p.inventory.selectedSlot = slot
        journal.log("tool: hotbar slot ${slot + 1}")
        return true
    }

    // ------------------------------------------------------------------ failsafe

    private fun staffNearby(): String? {
        val p = mc.player ?: return null
        val tags = WatchSigns.parseTags(staffTags.value)
        val range = staffRange.value.toDouble()
        for (o in p.level().players()) {
            if (o === p || o.distanceToSqr(p) > range * range) continue
            val shown = o.displayName?.string ?: o.name.string
            val tag = WatchSigns.staffTag(shown, tags)
                ?: mc.connection?.getPlayerInfo(o.uuid)?.tabListDisplayName?.string?.let { WatchSigns.staffTag(it, tags) }
            if (tag != null) return "[$tag] ${o.name.string}"
        }
        return null
    }

    // ------------------------------------------------------------------ HUD

    private fun hudLines(): List<String> {
        val ms = System.currentTimeMillis() - startMs
        val total = broken.values.sum()
        return listOf(
            "§dAuto Mining §7- $status",
            "§7Blocks §f$total §8(${"%,d".format(MiningMath.perHour(total, ms))}/h)",
        )
    }
}
//#endif
