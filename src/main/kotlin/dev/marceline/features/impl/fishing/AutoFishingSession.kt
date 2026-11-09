package dev.marceline.features.impl.fishing

//#if CHEAT
import dev.marceline.Marceline.mc
import dev.marceline.config.types.DropdownSetting
import dev.marceline.config.types.KeybindSetting
import dev.marceline.config.types.SliderSetting
import dev.marceline.config.types.ToggleSetting
import dev.marceline.event.impl.MainThreadPacketReceivedEvent
import dev.marceline.event.impl.TickEvent
import dev.marceline.event.impl.WorldChangeEvent
import dev.marceline.features.Feature
import dev.marceline.features.impl.routes.AssistBridge
import dev.marceline.features.impl.routes.AutoRoutes
import dev.marceline.utils.ChatUtils.modMessage
import dev.marceline.utils.ColorUtils.withAlpha
import dev.marceline.utils.GuiUtils
import dev.marceline.utils.MathUtils
import dev.marceline.utils.PlayerUtils
import dev.marceline.utils.items.ItemUtils.skyblockId
import dev.marceline.utils.render.Render2D.drawString
import java.util.Random
import kotlin.math.abs
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.tags.FluidTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.projectile.FishingHook
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3

import dev.marceline.utils.SkyblockText
import dev.marceline.ui.notification.NotificationManager
import dev.marceline.config.types.TextInputSetting

// Starting anywhere (walk to the nearest water / lava or hotspot), coming back after a server reboot,
// and the end-of-session report.

// ------------------------------------------------------------------ start anywhere

/** The fluid we're after, as the Fluid setting says (Auto: both). */
internal fun AutoFishing.wantedFluid(pos: BlockPos): Boolean {
    val f = mc.level?.getFluidState(pos) ?: return false
    if (f.isEmpty) return false
    return when (fluid.value) {
        1 -> f.`is`(FluidTags.WATER)
        2 -> f.`is`(FluidTags.LAVA)
        else -> true
    }
}

/** Already aiming at fishable water / lava within a cast's reach: fish right here. */
internal fun AutoFishing.lookingAtFluid(p: LocalPlayer): Vec3? {
    val eye = p.eyePosition
    val end = eye.add(p.lookAngle.scale(24.0))
    val hit = p.level().clip(net.minecraft.world.level.ClipContext(eye, end, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.ANY, p))
    if (hit.type != net.minecraft.world.phys.HitResult.Type.BLOCK) return null
    val bp = (hit as net.minecraft.world.phys.BlockHitResult).blockPos
    if (! wantedFluid(bp)) return null
    return if (solveAim(p, hit.location) != null) hit.location else null
}

/**
 * The nearest open surface of water / lava (a pool, not a lone source block or a trickle): fluid with air above
 * and at least 6 more surface blocks right around it.
 */
internal fun AutoFishing.nearestFluid(p: LocalPlayer, radius: Int): Vec3? {
    val lvl = p.level()
    val c = p.blockPosition()
    var best: BlockPos? = null
    var bestD = Double.MAX_VALUE
    fun surface(b: BlockPos) = wantedFluid(b) && lvl.getBlockState(b.above()).isAir
    for (dx in - radius .. radius) for (dz in - radius .. radius) {
        val flat = (dx * dx + dz * dz).toDouble()
        if (flat >= bestD || flat > radius * radius) continue
        for (dy in 6 downTo - 12) {
            val b = c.offset(dx, dy, dz)
            if (! surface(b)) continue
            var n = 0
            for (ox in - 2 .. 2) for (oz in - 2 .. 2) if ((ox != 0 || oz != 0) && surface(b.offset(ox, 0, oz))) n++
            if (n >= 6) { best = b; bestD = flat + dy * dy * 0.5 }
            break
        }
    }
    return best?.let { Vec3(it.x + 0.5, it.y + 0.88, it.z + 0.5) }
}

/** Where to fish when nothing better is known: here if we're looking at it, else walk to the nearest pool. */
/** Our feet on the ground: the block centre under us, dropping to the first solid block when we're in the air (max 6). */
internal fun AutoFishing.groundSpot(p: LocalPlayer): Vec3 {
    val lvl = p.level()
    var b = BlockPos.containing(p.position())
    if (! p.onGround()) for (i in 0 until 6) {
        val below = b.below()
        if (! lvl.getBlockState(below).getCollisionShape(lvl, below).isEmpty) break
        b = below
    }
    return Vec3.atBottomCenterOf(b)
}

internal fun AutoFishing.goToFluid(p: LocalPlayer) {
    val here = lookingAtFluid(p)
    // looking at it from high up: walk down to a spot just above it first (creatures can't reach a high ledge)
    if (here != null && moveDown.value && p.y - (surfaceY(here) ?: here.y) > maxHeight.value + 0.5) {
        val spot = shoreSpot(p, here)
        if (spot != null && spot.y < p.y - 1.0) {
            fishTarget = here
            stand = spot
            path = emptyList()
            travelStuck = 0
            state = FishingState.TRAVEL
            journal.log("standing ${"%.0f".format(p.y - (surfaceY(here) ?: here.y))} blocks above the ${fluidName()} - moving down to ${BlockPos.containing(spot).toShortString()}")
            info("&7Too high above the ${fluidName()} - moving down to fish.")
            return
        }
    }
    if (here != null) {
        stand = groundSpot(p)
        fishTarget = here
        aimAtPoint(p, here)
        state = FishingState.CAST
        status = "fishing where you look"
        return
    }
    val pool = nearestFluid(p, spotSearch.value)
    val spot = pool?.let { shoreSpot(p, it) }
    if (pool == null || spot == null) {
        stand = groundSpot(p)
        fishTarget = null
        state = FishingState.CAST
        info("&7No ${fluidName()} found within ${spotSearch.value} blocks - casting where you look.")
        return
    }
    fishTarget = pool
    stand = spot
    path = emptyList()
    travelStuck = 0
    state = FishingState.TRAVEL
    info("&7Going to the ${if (mc.level?.getFluidState(BlockPos.containing(pool))?.`is`(FluidTags.LAVA) == true) "lava" else "water"} &8(${p.position().distanceTo(pool).toInt()} blocks)")
}

/** Aim the casts at a point on the water (like [aimAt] does for a hotspot). */
internal fun AutoFishing.aimAtPoint(p: LocalPlayer, at: Vec3) {
    aimTarget = null
    val sol = solveAim(p, at)
    if (sol != null) { anchorYaw = sol.first; anchorPitch = sol.second }
    else {
        val rot = MathUtils.calcYawPitch(at)
        anchorYaw = rot.yaw
        anchorPitch = (rot.pitch - 8f).coerceIn(- 30f, 60f)
    }
    nextCastAim(p)
}

// ------------------------------------------------------------------ server reboot

/** The island name from the tab list ("Area: Crimson Isle"), if it shows one. */
internal fun AutoFishing.currentArea(): String? = runCatching {
    dev.marceline.utils.TabListUtils.getTabList().map { it.first.string.replace(Regex("§."), "").trim() }
        .firstNotNullOfOrNull { Regex("^(?:Area|Dungeon): (.+)$").find(it)?.groupValues?.get(1)?.trim() }
}.getOrNull()

/** The /warp for an island: the setting if filled in, else the usual SkyBlock one. */
internal fun AutoFishing.warpFor(area: String): String? {
    if (warpCommand.value.isNotBlank()) return warpCommand.value.trim().removePrefix("/")
    return SkyblockText.WARPS.entries.firstOrNull { area.contains(it.key, ignoreCase = true) }?.value
}

/** The reboot warning arrived: remember where we were, so a move in the next 3 minutes is followed back. */
internal fun AutoFishing.armReboot() {
    rebootAt = System.currentTimeMillis()
    rebootSpot = stand
    rebootHotspot = hotspot != null
    rebootArea = currentArea()
    journal.log("reboot warning: will come back to ${rebootArea ?: "?"} ${rebootSpot?.let { BlockPos.containing(it).toShortString() } ?: ""}")
}

internal fun AutoFishing.rebootWindow() = resumeAfterReboot.value && System.currentTimeMillis() - rebootAt < 180_000

/** Moved by the reboot: keep running, and come back. */
internal fun AutoFishing.beginResume() {
    state = FishingState.RESUME
    resumeStage = 0
    resumeWait = 100 + RANDOM.nextInt(300) // 5-20 s after the new world is in
    warpTries = 0
    expectedSlot = - 1
    hotspot = null
    path = emptyList()
    stopWalking()
    lastLookYaw = null
    ownMoveTicks = 100
    journal.log("moved by the reboot - coming back")
    NotificationManager.push("Server rebooted", "&fComing back to the fishing spot...")
}

/** One tick of coming back: wait for the world, warp to the island if needed, then walk / find the hotspot. */
internal fun AutoFishing.resumeTick() {
    val p = mc.player ?: return
    if (mc.level == null) return
    ownMoveTicks = 20
    lastLookYaw = null
    status = "coming back $resumeWhy"
    if (resumeWait > 0) { resumeWait--; return }
    val area = currentArea()
    val want = rebootArea
    if (want != null && (area == null || ! area.equals(want, ignoreCase = true))) {
        if (warpTries >= 2) return failStop("couldn't get back to $want after the reboot (now in ${area ?: "?"})")
        val cmd = warpFor(want) ?: return failStop("no warp known for $want - fill in Warp Command")
        warpTries++
        journal.log("warp $warpTries: /$cmd (in ${area ?: "?"}, want $want)")
        p.connection.sendCommand(cmd)
        resumeWait = 200 + RANDOM.nextInt(200)
        return
    }
    // on the island
    val spot = rebootSpot
    journal.log("back on ${area ?: "the island"}")
    if (rebootHotspot && hotspots.value) state = FishingState.FIND
    else if (spot != null && spot.distanceTo(p.position()) < 250) {
        stand = spot
        path = emptyList()
        travelStuck = 0
        state = FishingState.TRAVEL
    }
    else goToFluid(p)
    rebootAt = 0L
    info("&aBack $resumeWhy, fishing again.")
}

// ------------------------------------------------------------------ session report

/** End of a session: the numbers in chat and in this game's run log. */
internal fun AutoFishing.writeReport(reason: String) {
    if (sessionStart <= 0L) return
    val ms = System.currentTimeMillis() - sessionStart
    if (ms < 30_000 && reason == "stopped") { sessionStart = 0L; return } // a quick start / stop by hand isn't a session
    val hours = ms / 3_600_000.0
    val t = FishingTracker.numbers()
    val t0 = trackerAtStart
    fun d(k: String) = (t[k] ?: 0L) - (t0[k] ?: 0L)
    fun perH(n: Number) = if (hours > 0.01) "%,d".format((n.toDouble() / hours).toLong()) else "-"
    val coins = d("coins")
    val lines = buildList {
        add("Auto Fishing session  " + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
        add("Time        ${clock(ms)}  (breaks ${clock(breakMs)})")
        add("Where       ${currentArea() ?: "?"}  -  hotspots visited ${hotspotsVisited}")
        add("Casts       ${d("casts")}  ->  caught ${d("reels")}  (double hooks ${d("doubleHooks")})")
        add("Creatures   $creaturesCaught  (${perH(creaturesCaught)}/h)  killed $kills" + if (rareLog.isNotEmpty()) "  rare: ${rareLog.joinToString(", ")}" else "")
        add("Coins       %,d  (${perH(coins)}/h)".format(coins) + (FishingTracker.topItem()?.let { "  best: $it" } ?: ""))
        if (d("trophies") > 0) add("Trophy fish ${d("trophies")}")
        add("Ended       $reason")
        add("Problems    ground recasts $statGround, re-linked hooks $statRelink, splash recasts $statSplash, drift recasts $statDrift, lava escapes $statLava, deaths $statDeaths, stalls $statStalls")
    }
    if (chatInfo.value) dev.marceline.utils.ChatUtils.modBlock("&dSession report", lines.drop(1).map { "&7" + it.substringBefore("  ") + " &f" + it.substringAfter("  ").trim() })
    val file = dev.marceline.utils.Journal("report")
    file.log("=".repeat(60))
    lines.forEach { file.log(it) }
    file.log("=".repeat(60))
    lastReport = lines
    sessionStart = 0L
    sendDiscord(reason, lines, ms, coins, hours)
    // (the journal already writes into Downloads/Marceline/logs as it goes; copy only where that isn't possible)
    if (autoSaveLogs.value && ! dev.marceline.utils.Journal.mirroring) java.util.concurrent.CompletableFuture.runAsync {
        Thread.sleep(2000) // the journal writes in the background: let the last lines land first
        mc.execute { saveLogsToDownloads(false) }
    }
}
/**
 * The end-of-session Discord message: "goal reached" when it was, otherwise why it stopped - in both cases
 * with the report. One message per session.
 */
internal fun AutoFishing.sendDiscord(reason: String, lines: List<String>, ms: Long, coins: Long, hours: Double) {
    if (! discordOn.value || discordUrl.value.isBlank()) return
    val goal = reason.startsWith("done - goal reached")
    val manual = reason == "stopped"
    val watched = Regex("^stopped: (party invite from|private message from|staff nearby|teleported) ").containsMatchIn(reason)
    val title = when {
        watched -> "🚨 감지되어 멈춤"
        goal -> "🎯 목표 달성"
        manual -> "✋ 직접 멈춤"
        else -> "⛔ 낚시 멈춤"
    }
    val why = when {
        goal -> reason.substringAfter("(").substringBefore(")")
        manual -> "키를 눌러 멈췄어요"
        else -> reason.removePrefix("stopped: ")
    }
    val color = when { watched -> 0xF59E0B; goal -> 0x22C55E; manual -> 0x8B5CF6; else -> 0xEF4444 }
    fun perH(n: Long) = if (hours > 0.01) "%,d".format((n / hours).toLong()) else "-"
    val fields = listOf(
        "시간" to clock(ms),
        "바다 생물" to "$creaturesCaught (${perH(creaturesCaught.toLong())}/h)",
        "처치" to "$kills",
        "코인" to "%,d (%s/h)".format(coins, perH(coins)),
        "희귀" to (rareLog.joinToString(", ").ifBlank { "-" }),
        "지역" to (currentArea() ?: "-"),
        "위치" to (mc.player?.blockPosition()?.toShortString() ?: "-"),
        "시각" to java.time.LocalTime.now().withNano(0).toString(),
    )
    val desc = (if (goal) "**목표:** $why" else "**이유:** $why") + "\n```\n" + lines.drop(1).joinToString("\n") + "\n```"
    dev.marceline.utils.DiscordWebhook.send(discordUrl.value, title, desc, color, fields) { ok, err ->
        journal.log(if (ok) "discord: sent ($title)" else "discord: failed - $err")
    }
}
/** This game's run log and the one before it (session reports are inside them). */
internal fun AutoFishing.logFiles(): List<java.nio.file.Path> {
    val runs = dev.marceline.utils.Journal.runs()
    val cur = dev.marceline.utils.Journal.runFile
    return (listOf(cur).filter { java.nio.file.Files.exists(it) } + runs.filter { it != cur }).take(2)
}

/** Copies the logs into Downloads/Marceline/logs; [loud] says so in chat either way. */
internal fun AutoFishing.saveLogsToDownloads(loud: Boolean) {
    dev.marceline.utils.LogExport.copy(logFiles())
        .onSuccess { if (loud) info("&aLogs saved to &f$it") else journal.log("logs copied to $it") }
        .onFailure { info("&cCouldn't save to Downloads: ${it.message}") }
}

internal fun AutoFishing.sendLogs() {
    if (discordUrl.value.isBlank()) return info("&cPut your Discord webhook URL in first (Discord section).")
    val files = ArrayList<Pair<String, ByteArray>>()
    for (f in logFiles()) runCatching {
        val log = java.nio.file.Files.readAllBytes(f)
        files += f.fileName.toString() to (if (log.size > 1_500_000) log.copyOfRange(log.size - 1_500_000, log.size) else log)
    }
    if (files.isEmpty()) return info("&cNo logs yet - fish for a bit first.")
    info("&7Sending ${files.size} file(s) to Discord...")
    dev.marceline.utils.DiscordWebhook.sendFiles(discordUrl.value, "🎣 Marceline 로그 (${java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))})", files) { ok, why ->
        info(if (ok) "&aLogs sent to Discord." else "&cSending failed: $why")
    }
}


internal fun AutoFishing.stateLabel() = when (state) {
    FishingState.FIND -> "Looking for a hotspot"
    FishingState.TRAVEL -> "Travelling"
    FishingState.CAST -> if (returning) "Re-aiming" else "Casting"
    FishingState.WAIT_BITE -> "Waiting"
    FishingState.REEL -> "Reeling"
    FishingState.FIGHT -> "Attacking"
    FishingState.SELL -> "Selling"
    FishingState.BREAK -> "On a break"
    FishingState.RESUME -> "Coming back"
}


internal fun AutoFishing.clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "${s / 3600}h ${(s / 60) % 60}m" else if (s >= 60) "${s / 60}m ${s % 60}s" else "${s}s"
}


/** The status card: icon, state, kills per hour big, then sea creatures / session / per hour / next break. */
internal fun AutoFishing.drawCard(ctx: net.minecraft.client.gui.GuiGraphicsExtractor, example: Boolean): Pair<Number, Number> {
    val st = dev.marceline.ui.clickgui.components.settings.Style
    val w = 150f
    val stats = if (example) "SCC 21%  DHC 8%  Speed 120" else tabStats
    val h = if (stats.isNotEmpty()) 104f else 92f
    val now = System.currentTimeMillis()
    val session = if (example) 754_000L else now - sessionStart
    val hours = session / 3_600_000.0
    val killsHr = if (example) 1_412 else if (hours > 0.003) (kills / hours).toInt() else 0
    val label = if (example) "Waiting" else stateLabel()
    val breakText = when {
        example -> "21m 4s"
        ! longBreaks.value -> "off"
        state == FishingState.BREAK -> "now " + clock(breakUntil - now)
        nextBreakAt > 0 -> clock(nextBreakAt - now)
        else -> "-"
    }
    st.drawShadow(ctx, 0f, 0f, w, h, 6f, 0.6f)
    st.roundRect(ctx, 0f, 0f, w, h, 6f, java.awt.Color(15, 12, 13, 225))
    st.roundRect(ctx, 10f, 1f, w - 20f, 0.75f, 0.4f, java.awt.Color(255, 255, 255, 22))
    // icon in an accent ring
    st.roundRect(ctx, 7f, 7f, 18f, 18f, 9f, st.accentColor)
    st.roundRect(ctx, 8.5f, 8.5f, 15f, 15f, 7.5f, java.awt.Color(15, 12, 13))
    ctx.pose().pushMatrix()
    ctx.pose().translate(10.5f, 10.5f)
    ctx.pose().scale(11f / 64f, 11f / 64f)
    with(dev.marceline.utils.render.Render2D) { ctx.drawTexture(fishIcon, 0, 0, 64, 64, st.accentColor) }
    ctx.pose().popMatrix()
    ctx.drawString("§lAuto Fishing", 30f, 8f, st.mix(st.accentColor, java.awt.Color.WHITE, 0.35f), shadow = false)
    ctx.drawString(label, 30f, 17.5f, java.awt.Color(150, 140, 175), shadow = false, scale = 0.9f)

    ctx.drawString("KILLS / HR", 8f, 32f, java.awt.Color(110, 100, 135), shadow = false, scale = 0.75f)
    // the headline number, a size up
    ctx.pose().pushMatrix()
    ctx.pose().translate(8f, 39.5f)
    ctx.pose().scale(1.55f, 1.55f)
    st.drawTabular(ctx, "%,d".format(killsHr), 0f, 0f, java.awt.Color(230, 224, 245))
    ctx.pose().popMatrix()
    fun cell(title: String, value: String, x: Float, y: Float, right: Boolean) {
        val tw = with(dev.marceline.utils.render.RenderHelper) { title.width() } * 0.7f
        val vw = with(dev.marceline.utils.render.RenderHelper) { value.width() } * 0.9f
        ctx.drawString(title, if (right) x - tw else x, y, java.awt.Color(110, 100, 135), shadow = false, scale = 0.7f)
        ctx.drawString(value, if (right) x - vw else x, y + 7.5f, java.awt.Color(214, 206, 232), shadow = false, scale = 0.9f)
    }
    cell("SEA CREATURES", if (example) "38" else "$creaturesCaught", 8f, 55f, false)
    cell("SESSION TIME", clock(session), w - 8f, 55f, true)
    cell("KILLS", if (example) "36" else "$kills", 8f, 73f, false)
    cell("NEXT BREAK", breakText, w - 8f, 73f, true)
    if (stats.isNotEmpty()) {
        st.roundRect(ctx, 8f, 91f, w - 16f, 0.5f, 0.25f, java.awt.Color(255, 255, 255, 18))
        ctx.drawString(stats, 8f, 94f, java.awt.Color(150, 140, 175), shadow = false, scale = 0.7f)
    }
    return w to h
}


/** Sea Creature Chance / Double Hook Chance / Fishing Speed from the tab list's stat lines, if it shows them. */
internal fun AutoFishing.readTabStats(): String = runCatching {
    val lines = dev.marceline.utils.TabListUtils.getTabList().map { it.first.string.replace(Regex("§."), "").trim() }
    fun stat(key: String) = lines.firstNotNullOfOrNull { Regex("$key:?\\s*([\\d.,]+%?)").find(it)?.groupValues?.get(1) }
    listOfNotNull(stat("Sea Creature Chance")?.let { "SCC $it" }, stat("Double Hook Chance")?.let { "DHC $it" }, stat("Fishing Speed")?.let { "Speed $it" })
        .joinToString("  ")
}.getOrDefault("")

//#endif
