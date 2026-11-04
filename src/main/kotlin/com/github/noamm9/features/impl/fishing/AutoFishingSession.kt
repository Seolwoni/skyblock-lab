package com.github.noamm9.features.impl.fishing

//#if CHEAT
import com.github.noamm9.NoammAddons.mc
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
import com.github.noamm9.utils.ChatUtils.modMessage
import com.github.noamm9.utils.ColorUtils.withAlpha
import com.github.noamm9.utils.GuiUtils
import com.github.noamm9.utils.MathUtils
import com.github.noamm9.utils.PlayerUtils
import com.github.noamm9.utils.items.ItemUtils.skyblockId
import com.github.noamm9.utils.render.Render2D.drawString
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

import com.github.noamm9.utils.SkyblockText
import com.github.noamm9.ui.notification.NotificationManager

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
internal fun AutoFishing.goToFluid(p: LocalPlayer) {
    val here = lookingAtFluid(p)
    if (here != null) {
        stand = Vec3.atBottomCenterOf(BlockPos.containing(p.position()))
        fishTarget = here
        aimAtPoint(p, here)
        state = AutoFishing.State.CAST
        status = "fishing where you look"
        return
    }
    val pool = nearestFluid(p, spotSearch.value)
    val spot = pool?.let { shoreSpot(p, it) }
    if (pool == null || spot == null) {
        stand = Vec3.atBottomCenterOf(BlockPos.containing(p.position()))
        fishTarget = null
        state = AutoFishing.State.CAST
        info("&7No ${fluidName()} found within ${spotSearch.value} blocks - casting where you look.")
        return
    }
    fishTarget = pool
    stand = spot
    path = emptyList()
    travelStuck = 0
    state = AutoFishing.State.TRAVEL
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
    com.github.noamm9.utils.TabListUtils.getTabList().map { it.first.string.replace(Regex("§."), "").trim() }
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
    state = AutoFishing.State.RESUME
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
    status = "coming back after the reboot"
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
    if (rebootHotspot && hotspots.value) state = AutoFishing.State.FIND
    else if (spot != null && spot.distanceTo(p.position()) < 250) {
        stand = spot
        path = emptyList()
        travelStuck = 0
        state = AutoFishing.State.TRAVEL
    }
    else goToFluid(p)
    rebootAt = 0L
    info("&aBack after the reboot, fishing again.")
}

// ------------------------------------------------------------------ session report

/** End of a session: the numbers in chat and in journal/sessions/<date>.txt. */
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
        add("Problems    ground recasts $statGround, re-linked hooks $statRelink, splash recasts $statSplash, drift recasts $statDrift, lava escapes $statLava")
    }
    info("&d&l--- Session report ---")
    for (l in lines.drop(1)) info("&7" + l.substringBefore("  ") + " &f" + l.substringAfter("  ").trim())
    val name = "sessions/" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))
    val file = com.github.noamm9.utils.Journal(name)
    lines.forEach { file.log(it) }
    journal.log("report saved: $name")
    lastReport = lines
    sessionStart = 0L
    sendDiscord(reason, lines, ms, coins, hours)
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
    com.github.noamm9.utils.DiscordWebhook.send(discordUrl.value, title, desc, color, fields) { ok, err ->
        journal.log(if (ok) "discord: sent ($title)" else "discord: failed - $err")
    }
}
/** fishing.log (last ~1.5 MB) and the 3 newest session reports, to the Discord webhook. */
internal fun AutoFishing.sendLogs() {
    if (discordUrl.value.isBlank()) return info("&cPut your Discord webhook URL in first (Discord section).")
    val dir = java.nio.file.Path.of(journal.path).parent
    val files = ArrayList<Pair<String, ByteArray>>()
    runCatching {
        val log = java.nio.file.Files.readAllBytes(dir.resolve("fishing.log"))
        files += "fishing.log" to (if (log.size > 1_500_000) log.copyOfRange(log.size - 1_500_000, log.size) else log)
    }
    runCatching {
        java.nio.file.Files.list(dir.resolve("sessions")).use { st ->
            st.sorted(Comparator.reverseOrder()).limit(3).forEach { files += it.fileName.toString() to java.nio.file.Files.readAllBytes(it) }
        }
    }
    if (files.isEmpty()) return info("&cNo logs yet - fish for a bit first.")
    info("&7Sending ${files.size} file(s) to Discord...")
    com.github.noamm9.utils.DiscordWebhook.sendFiles(discordUrl.value, "🎣 Riftwalk 로그 (${java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))})", files) { ok, why ->
        info(if (ok) "&aLogs sent to Discord." else "&cSending failed: $why")
    }
}
//#endif
