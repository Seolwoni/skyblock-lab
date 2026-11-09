package dev.marceline.features.impl.mining

//#if CHEAT
import dev.marceline.config.types.DropdownSetting
import dev.marceline.config.types.KeybindSetting
import dev.marceline.config.types.MultiCheckboxSetting
import dev.marceline.config.types.SliderSetting
import dev.marceline.config.types.TextInputSetting
import dev.marceline.config.types.ToggleSetting
import dev.marceline.event.impl.ChatMessageEvent
import dev.marceline.event.impl.MainThreadPacketReceivedEvent
import dev.marceline.event.impl.TickEvent
import dev.marceline.event.impl.WorldChangeEvent
import dev.marceline.features.Feature
import dev.marceline.ui.notification.NotificationManager
import dev.marceline.utils.ChatUtils
import dev.marceline.utils.HumanProfile
import dev.marceline.utils.HumanTiming
import dev.marceline.utils.Humanize
import dev.marceline.utils.Journal
import dev.marceline.utils.MathUtils
import dev.marceline.utils.PlayerUtils
import dev.marceline.utils.WatchSigns
import dev.marceline.utils.items.ItemUtils.skyblockId
import dev.marceline.utils.render.Render2D.drawString
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
    private val mode by DropdownSetting("Mode", 0, listOf("Mithril", "Gemstone", "Commissions")).withDescription("Mithril: Dwarven Mines mithril and titanium. Gemstone: the gem colours ticked below. Commissions: reads your commissions, walks to the area, mines, claims, repeats")

    private val titanium by ToggleSetting("Titanium", true).section("Mithril").showIf { mode.value == 0 }
    private val blue by ToggleSetting("Blue Mithril", true).showIf { mode.value == 0 }.withDescription("Light blue wool, worth the most mithril")
    private val green by ToggleSetting("Green Mithril", true).showIf { mode.value == 0 }.withDescription("Prismarine")
    private val grey by ToggleSetting("Grey Mithril", true).showIf { mode.value == 0 }.withDescription("Grey wool")

    private val gems by MultiCheckboxSetting("Gemstones", MiningMath.GEMSTONES.associate { it.name to true }.toMutableMap()).section("Gemstone").showIf { mode.value == 1 }

    private val recordKey by KeybindSetting("Record Route Key").section("Commissions").showIf { mode.value == 2 }.withDescription(
        "Right after the warp (see Warp Command), press it, walk to a good mining spot in an area and press it again: the route is saved for that area (from the scoreboard). One route per area")
    private val warpCommand by TextInputSetting("Warp Command", "/warp forge").showIf { mode.value == 2 }.withDescription("Where every route starts")
    private val claimWith by DropdownSetting("Claim With", 0, listOf("Royal Pigeon", "Command")).showIf { mode.value == 2 }.withDescription("Royal Pigeon: right-clicks it from the hotbar. Command: types the command below")
    private val claimCommand by TextInputSetting("Claim Command", "/commissions").showIf { mode.value == 2 && claimWith.value == 1 }
    private val sprint by ToggleSetting("Sprint On Routes", true).showIf { mode.value == 2 }
    private val listRoutes by dev.marceline.config.types.ButtonSetting("List Routes") { showRoutes() }.showIf { mode.value == 2 }
    private val deleteRoute by dev.marceline.config.types.ButtonSetting("Delete Route Of This Area") { deleteHereRoute() }.showIf { mode.value == 2 }

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

    // commissions
    private enum class C { PICK, WARP, WALK, MINE, CLAIM }
    private val routes = dev.marceline.config.PogObject("mining_routes", mutableMapOf<String, String>())
    private var recording: MutableList<CommissionMath.Point>? = null
    private var cState = C.PICK
    private var cWait = 0
    private var cName: String? = null
    private var cTask: CommissionMath.Task? = null
    private var cArea: String? = null
    private var route: List<CommissionMath.Point> = emptyList()
    private var routeIndex = 0
    private var walkBest = Double.MAX_VALUE
    private var walkSince = 0
    private var walkFails = 0
    private var walkPitch = 10f
    private var ownMoveUntil = 0
    private var claimOpenedAt = 0
    private var claimClicks = 0
    private var claimed = 0
    private var claimFails = 0

    override fun init() {
        register<TickEvent.Start> {
            if (toggleKey.isPressed() && mc.gui.screen() == null) {
                if (running) stop("stopped") else start()
            }
            if (recordKey.isPressed() && mc.gui.screen() == null) toggleRecording()
            recording?.let { rec -> mc.player?.let { p -> CommissionMath.Point(p.x, p.y, p.z).let { now -> if (CommissionMath.shouldRecord(rec.lastOrNull(), now)) rec += now } } }
            if (running) runCatching(::step).onFailure { dev.marceline.utils.Safely.report("AutoMining tick", it); stop("error: ${it.message}") }
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
        register<WorldChangeEvent> {
            recording?.let { recording = null; info("&eRoute recording dropped (world changed). Start it again after the warp.") }
            if (running && tick >= ownMoveUntil) stop("world changed")
        }
        register<MainThreadPacketReceivedEvent.Pre> {
            if (! running || ! failsafe.value || ! stopOnMoved.value || tick < ownMoveUntil) return@register
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
        if (mode.value != 2 && kinds().isEmpty()) return info("&cNothing to mine: tick at least one block kind in the settings.")
        if (mode.value == 2 && routes.get().isEmpty()) return info("&cNo routes yet: record one per area with the Record Route Key (Commissions section).")
        profile = Humanize.profile()
        timing = HumanTiming().apply(profile)
        tick = 0; target = null; targetKind = null; wait = 0; idleSince = - 1
        skipped.clear(); broken.clear(); breakTicks.clear()
        stallSig = ""; stallSince = 0; stalls.clear(); statStalls = 0; statSkips = 0; statContested = 0; fullSince = - 1
        startMs = System.currentTimeMillis()
        abilityReady = true; abilityTriedAt = - 10_000
        cState = C.PICK; cWait = 0; cName = null; cTask = null; cArea = null; route = emptyList(); routeIndex = 0
        walkFails = 0; ownMoveUntil = 0; claimed = 0; claimFails = 0
        running = true
        journal.log("==== start  mode=${listOf("Mithril", "Gemstone", "Commissions")[mode.value]}  kinds=${kinds().joinToString { it.name }}  at ${p.blockPosition().toShortString()}  profile=$profile")
        info("&aAuto Mining started. Press the key again to stop.")
    }

    private fun failStop(why: String) {
        journal.log("FAILSAFE  $why")
        stop("stopped: $why")
        NotificationManager.push("Auto Mining stopped", why)
        dev.marceline.utils.SoundUtils.playSound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value(), 1f, 0.6f)
    }

    private fun stop(reason: String?) {
        if (running) report(reason ?: "stopped")
        running = false
        target = null
        mc.options.keyAttack.isDown = false
        stopWalking()
        if (reason != null) info("&eAuto Mining $reason.")
    }

    private fun report(reason: String) {
        val ms = System.currentTimeMillis() - startMs
        val total = broken.values.sum()
        val lines = buildList {
            add("&7Time &f${ms / 60_000}m ${(ms / 1000) % 60}s")
            add("&7Blocks &f$total &8(${"%,d".format(MiningMath.perHour(total, ms))}/h)")
            if (broken.isNotEmpty()) add("&7By kind &f" + broken.entries.joinToString(", ") { "${it.key} ${it.value} (${MiningMath.median(breakTicks[it.key]?.toList().orEmpty())}t)" })
            if (mode.value == 2) add("&7Commissions &f$claimed claimed")
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
        if (failsafe.value && stopOnStaff.value && tick % 20 == 0) staffNearby()?.let { return failStop("staff nearby: $it") }
        if (mode.value == 2 && commissionTick(p)) return
        if (mc.gui.screen() != null) { release(); status = "paused (menu open)"; return }
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
        1 -> MiningMath.GEMSTONES.filter { gems.value[it.name] == true }
        else -> when (cTask?.job) {
            CommissionMath.Job.TITANIUM -> listOf(MiningMath.TITANIUM, MiningMath.MITHRIL_BLUE, MiningMath.MITHRIL_GREEN, MiningMath.MITHRIL_GREY)
            CommissionMath.Job.MITHRIL -> listOf(MiningMath.MITHRIL_BLUE, MiningMath.MITHRIL_GREEN, MiningMath.MITHRIL_GREY)
            else -> emptyList()
        }
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
        return listOfNotNull(
            "§dAuto Mining §7- $status",
            cName?.takeIf { mode.value == 2 }?.let { "§7Commission §f$it §8($claimed claimed)" },
            "§7Blocks §f$total §8(${"%,d".format(MiningMath.perHour(total, ms))}/h)",
        )
    }

    // ------------------------------------------------------------------ commissions

    private fun clean(s: String) = s.replace(Regex("§."), "").replace(Regex("[^\\x20-\\x7E]"), "").trim()

    private fun tabLines(): List<String> = runCatching { dev.marceline.utils.TabListUtils.getTabList().map { it.first.string.replace(Regex("§."), "") } }.getOrDefault(emptyList())

    /** The area under the island on the scoreboard ("⏣ Royal Mines" -> "Royal Mines"). */
    private fun hereArea(): String? = runCatching {
        dev.marceline.utils.ScoreboardUtils.getLines().map { it.string.replace(Regex("§."), "") }
            .firstOrNull { it.contains('⏣') || it.contains('ф') }?.let { clean(it.substringAfter('⏣').substringAfter('ф')) }?.ifEmpty { null }
    }.getOrNull()

    private fun routeNames() = routes.get().keys.toList()

    private fun toggleRecording() {
        val p = mc.player ?: return
        val rec = recording
        if (rec == null) {
            recording = mutableListOf(CommissionMath.Point(p.x, p.y, p.z))
            info("&aRecording a route. Walk to the mining spot, then press the key again.")
            return
        }
        recording = null
        rec += CommissionMath.Point(p.x, p.y, p.z)
        if (rec.size < 2) return info("&cToo short, nothing saved.")
        val area = hereArea() ?: "Route ${routes.get().size + 1}"
        routes.get()[area] = CommissionMath.format(rec)
        routes.save()
        journal.log("route saved: $area, ${rec.size} points, from ${"%.0f %.0f %.0f".format(rec.first().x, rec.first().y, rec.first().z)}")
        info("&aSaved the route to &f$area &7(${rec.size} points)")
    }

    private fun showRoutes() {
        val r = routes.get()
        if (r.isEmpty()) return info("&7No routes yet.")
        ChatUtils.modBlock("&dMining routes", r.map { (k, v) -> "&f$k &7(${CommissionMath.parseRoute(v).size} points)" })
    }

    private fun deleteHereRoute() {
        val area = hereArea() ?: return info("&cCan't tell which area this is from the scoreboard.")
        if (routes.get().remove(area) != null) { routes.save(); info("&eDeleted the route of &f$area") } else info("&7No route for &f$area")
    }

    /** Commission mode. True when this tick was used for commission work (not mining). */
    private fun commissionTick(p: net.minecraft.client.player.LocalPlayer): Boolean {
        if (cWait > 0) { cWait--; return cState != C.MINE }
        when (cState) {
            C.PICK -> pickCommission(p)
            C.WARP -> {
                // after the warp: join the route at its nearest point if we're close to it
                val at = CommissionMath.Point(p.x, p.y, p.z)
                val i = CommissionMath.joinAt(route, at)
                if (route[i].dist(at) > 12) return true.also { failStop("the route to $cArea doesn't start near where ${warpCommand.value} put us") }
                routeIndex = i; walkBest = Double.MAX_VALUE; walkSince = tick
                cState = C.WALK
                status = "walking to $cArea"
            }
            C.WALK -> walkRoute(p)
            C.MINE -> {
                if (tick % 40 == 0) {
                    val list = CommissionMath.parse(tabLines())
                    if (list.any { it.done }) {
                        journal.log("commission done: ${list.filter { it.done }.joinToString { it.name }}")
                        release()
                        cState = C.CLAIM; claimOpenedAt = - 1; claimClicks = 0
                        cWait = Humanize.reaction()
                        return true
                    }
                }
                return false // mine
            }
            C.CLAIM -> claimTick(p)
        }
        return true
    }

    private fun pickCommission(p: net.minecraft.client.player.LocalPlayer) {
        val list = CommissionMath.parse(tabLines())
        if (list.isEmpty()) return failStop("no Commissions in the tab list (turn the Commissions widget on in /tab)")
        if (list.any { it.done }) { cState = C.CLAIM; claimOpenedAt = - 1; claimClicks = 0; return }
        val here = hereArea()
        val next = CommissionMath.pickNext(list, routes.get().keys, here)
            ?: return failStop("no commission I can do: ${list.joinToString { it.name }} (record routes for their areas)")
        val task = CommissionMath.taskOf(next.name)
        val area = CommissionMath.areaFor(task, routeNames(), here) ?: return failStop("no route for ${next.name}")
        cName = next.name; cTask = task; cArea = area
        route = CommissionMath.parseRoute(routes.get()[area].orEmpty())
        if (route.size < 2) return failStop("the route for $area is broken, record it again")
        target = null
        journal.log("commission: ${next.name} (${(next.progress * 100).toInt()}%) -> $area, job ${task.job}, here=$here")
        // already standing at the end of that route: just mine
        if (route.last().dist(CommissionMath.Point(p.x, p.y, p.z)) < 6) { cState = C.MINE; status = "mining for $cName"; return }
        p.connection.sendCommand(warpCommand.value.removePrefix("/"))
        ownMoveUntil = tick + 200
        journal.log("warp: ${warpCommand.value}")
        cState = C.WARP
        cWait = 60 + Humanize.reaction()
        status = "warping"
    }

    private fun walkRoute(p: net.minecraft.client.player.LocalPlayer) {
        ownMoveUntil = tick + 40
        val goal = route[routeIndex]
        val at = CommissionMath.Point(p.x, p.y, p.z)
        val last = routeIndex == route.size - 1
        if (goal.flat(at) < (if (last) 0.8 else 1.4) && kotlin.math.abs(goal.y - at.y) < 1.6) {
            if (last) {
                stopWalking()
                journal.log("arrived at $cArea")
                cState = C.MINE
                cWait = Humanize.reaction()
                status = "mining for $cName"
                return
            }
            routeIndex++; walkBest = Double.MAX_VALUE; walkSince = tick
            return
        }
        val d = goal.dist(at)
        if (d < walkBest - 0.3) { walkBest = d; walkSince = tick }
        if (tick - walkSince > 160) {
            stopWalking()
            walkFails++
            journal.log("stuck on the way to $cArea at ${"%.0f %.0f %.0f".format(at.x, at.y, at.z)} (point ${routeIndex + 1}/${route.size}, ${"%.1f".format(d)} left), fail $walkFails")
            if (walkFails >= 3) return failStop("stuck 3 times on the route to $cArea")
            cState = C.PICK
            cWait = 20 + Humanize.reaction()
            return
        }
        val yaw = Math.toDegrees(kotlin.math.atan2(goal.z - p.z, goal.x - p.x)).toFloat() - 90f
        if (rnd.nextInt(120) == 0) walkPitch = 6f + rnd.nextFloat() * 10f
        Humanize.turnTo(p, yaw, walkPitch, tolerance = 3f, overshoot = false)
        var off = (yaw - p.yRot) % 360f
        if (off > 180f) off -= 360f
        if (off < - 180f) off += 360f
        off = kotlin.math.abs(off)
        mc.options.keyUp.isDown = off < 35f
        mc.options.keyJump.isDown = p.onGround() && (goal.y - p.y > 0.6 || (p.horizontalCollision && tick - walkSince > 10))
        mc.options.keySprint.isDown = sprint.value && off < 12f && route.size - routeIndex > 2
        status = "walking to $cArea (${routeIndex + 1}/${route.size})"
    }

    private fun stopWalking() {
        mc.options.keyUp.isDown = false
        mc.options.keyJump.isDown = false
        mc.options.keySprint.isDown = false
    }

    private fun claimTick(p: net.minecraft.client.player.LocalPlayer) {
        release()
        val screen = mc.gui.screen() as? net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<*>
        if (claimOpenedAt < 0) {
            // open the commissions menu
            if (mc.gui.screen() != null) { p.closeContainer(); cWait = 5; return }
            if (claimWith.value == 0) {
                val slot = (0 .. 8).firstOrNull { p.inventory.getItem(it).skyblockId == "ROYAL_PIGEON" }
                    ?: return failStop("no Royal Pigeon in the hotbar (or set Claim With to Command)")
                p.inventory.selectedSlot = slot
                cWait = Humanize.reaction().coerceAtLeast(3)
                claimOpenedAt = tick + cWait
                mc.execute { PlayerUtils.rightClick() }
            } else {
                p.connection.sendCommand(claimCommand.value.removePrefix("/"))
                claimOpenedAt = tick
            }
            status = "claiming"
            return
        }
        if (screen == null || ! clean(screen.title.string).contains("Commission", ignoreCase = true)) {
            if (claimOpenedAt in 0 until tick - 80) {
                claimOpenedAt = - 1
                if (++claimFails >= 3) return failStop("the commissions menu didn't open")
                journal.log("commissions menu didn't open, trying again")
            }
            return
        }
        val top = p.containerMenu.slots.filter { it.container !is net.minecraft.world.entity.player.Inventory }
        if (top.none { ! it.item.isEmpty }) return // contents not here yet
        val done = top.firstOrNull { s ->
            val lore = with(dev.marceline.utils.items.ItemUtils) { s.item.lore }.joinToString(" ") { clean(it) }
            lore.contains("COMPLETED", ignoreCase = true) || lore.contains("Click to claim", ignoreCase = true)
        }
        if (done != null && claimClicks < 8) {
            dev.marceline.utils.GuiUtils.clickSlot(done.index, dev.marceline.utils.GuiUtils.ButtonType.LEFT)
            claimClicks++
            claimed++
            journal.log("claimed: ${clean(done.item.hoverName.string)}")
            cWait = timing.next(6, 16)
            return
        }
        p.closeContainer()
        claimFails = 0
        selectTool()
        cState = C.PICK
        cWait = Humanize.reaction() + 10 // let the tab list catch up with the new commissions
        status = "picking the next commission"
    }
}
//#endif
