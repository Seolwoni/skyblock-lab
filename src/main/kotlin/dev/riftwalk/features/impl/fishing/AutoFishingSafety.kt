package dev.riftwalk.features.impl.fishing

//#if CHEAT
import dev.riftwalk.Riftwalk.mc
import dev.riftwalk.config.types.DropdownSetting
import dev.riftwalk.config.types.KeybindSetting
import dev.riftwalk.config.types.SliderSetting
import dev.riftwalk.config.types.ToggleSetting
import dev.riftwalk.event.impl.MainThreadPacketReceivedEvent
import dev.riftwalk.event.impl.TickEvent
import dev.riftwalk.event.impl.WorldChangeEvent
import dev.riftwalk.features.Feature
import dev.riftwalk.features.impl.routes.AssistBridge
import dev.riftwalk.features.impl.routes.AutoRoutes
import dev.riftwalk.ui.notification.NotificationManager
import dev.riftwalk.utils.ChatUtils.modMessage
import dev.riftwalk.utils.ColorUtils.withAlpha
import dev.riftwalk.utils.GuiUtils
import dev.riftwalk.utils.MathUtils
import dev.riftwalk.utils.PlayerUtils
import dev.riftwalk.utils.items.ItemUtils.skyblockId
import dev.riftwalk.utils.render.Render2D.drawString
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
import dev.riftwalk.config.types.TextInputSetting

// Staying safe: standing in the middle of a safe block, Hyperion blasts that can't land in lava, getting out of lava.

/** In lava: jump and walk back to the last safe ground; stop if it doesn't work out. */
internal fun AutoFishing.escapeLava(p: LocalPlayer) {
    ownMoveTicks = 20
    if (p.fishing != null) rod()?.let { hold(it); use(p) }
    val back = lastSafe
    status = "in lava, getting out"
    if (lavaTicks == 0) statLava++
    lavaTicks++
    mc.options.keyJump.isDown = true
    mc.options.keySprint.isDown = true
    if (back != null) {
        // aim at the last safe spot, a little above it so a teleport lands on top
        val target = back.add(0.0, 1.2, 0.0)
        val rot = MathUtils.calcYawPitch(target)
        dev.riftwalk.utils.Humanize.setView(p, rot.yaw, rot.pitch)
        mc.options.keyUp.isDown = true
        // swimming alone often can't climb out: teleport back with AOTV, else the Hyperion, every half second
        if (lavaTicks % 10 == 4) {
            val tp = PlayerUtils.findHotbarSlot { it.skyblockId in dev.riftwalk.utils.SkyblockText.TELEPORT_SWORDS }
                ?: PlayerUtils.findHotbarSlot { it.skyblockId in WITHER_BLADES }
            if (tp != null) { hold(tp); use(p) }
        }
    }
    if (lavaTicks > 200) {
        stopWalking()
        failStop("fell into lava and couldn't get out")
        return
    }
    state = FishingState.TRAVEL
    repath = 0
}

/** A block you can safely stand in: solid, non-lava ground under it, room for the body, no lava in it. */
internal fun AutoFishing.safeStand(lvl: net.minecraft.world.level.Level, b: BlockPos): Boolean {
    fun solid(x: BlockPos) = ! lvl.getBlockState(x).getCollisionShape(lvl, x).isEmpty
    fun lava(x: BlockPos) = lvl.getFluidState(x).`is`(FluidTags.LAVA)
    val under = b.below()
    return solid(under) && ! lava(under) && ! solid(b) && ! solid(b.above()) && ! lava(b) && ! lava(b.above())
}

/**
 * Keeps us in the exact middle of a safe block. Moves with WASD relative to where we're already looking
 * (no turning), sneaking so it can never walk off an edge. True once we're there.
 */
internal fun AutoFishing.centered(p: LocalPlayer): Boolean {
    if (! standCentre.value || ! p.onGround()) return true
    val lvl = p.level()
    val here = BlockPos.containing(p.position())
    var target = centreTarget
    if (target == null || BlockPos.containing(target).distManhattan(here) > 2) {
        val pick = (listOf(here) + listOf(here.north(), here.south(), here.east(), here.west(),
            here.north().east(), here.north().west(), here.south().east(), here.south().west()))
            .filter { safeStand(lvl, it) }
            .minByOrNull { Vec3.atBottomCenterOf(it).distanceToSqr(p.position()) }
            ?: return true // nowhere better nearby: stay
        target = Vec3.atBottomCenterOf(pick)
        centreTarget = target
    }
    val dx = target.x - p.x
    val dz = target.z - p.z
    if (dx * dx + dz * dz < 0.1 * 0.1 || ++centreTicks > 100) {
        releaseCentre()
        centreTicks = 0
        return true
    }
    val yr = Math.toRadians(p.yRot.toDouble())
    val f = dx * - kotlin.math.sin(yr) + dz * kotlin.math.cos(yr) // along where we look
    val r = dx * - kotlin.math.cos(yr) + dz * - kotlin.math.sin(yr) // to our right
    ownMoveTicks = 20
    mc.options.keyShift.isDown = true
    mc.options.keyUp.isDown = f > 0.05
    mc.options.keyDown.isDown = f < - 0.05
    mc.options.keyRight.isDown = r > 0.05
    mc.options.keyLeft.isDown = r < - 0.05
    return false
}

internal fun AutoFishing.releaseCentre() {
    mc.options.keyShift.isDown = false
    mc.options.keyUp.isDown = false
    mc.options.keyDown.isDown = false
    mc.options.keyRight.isDown = false
    mc.options.keyLeft.isDown = false
}

/**
 * True when a Wither Impact with this look can't drop us into lava: follows the 10-block teleport until
 * something blocks the body, then checks there's solid ground (not lava) under where we'd end up.
 */
internal fun AutoFishing.blastSafe(p: LocalPlayer, yaw: Float, pitch: Float): Boolean {
    val lvl = p.level()
    val look = Vec3.directionFromRotation(pitch, yaw)
    val eyeH = p.eyeHeight.toDouble()
    fun solid(b: BlockPos) = ! lvl.getBlockState(b).getCollisionShape(lvl, b).isEmpty
    fun lava(b: BlockPos) = lvl.getFluidState(b).`is`(FluidTags.LAVA)
    // the whole body, not just its middle: the centre and the four corners of the hitbox must all end up safe
    fun safeFrom(ox: Double, oz: Double): Boolean {
        val eye = p.eyePosition.add(ox, 0.0, oz)
        var end = p.position().add(ox, 0.0, oz)
        var d = 0.25
        while (d <= 10.0) {
            val feet = eye.add(look.scale(d)).subtract(0.0, eyeH, 0.0)
            val bp = BlockPos.containing(feet)
            if (solid(bp) || solid(bp.above())) break
            end = feet
            d += 0.25
        }
        val at = BlockPos.containing(end)
        if (lava(at) || lava(at.above())) return false
        for (dy in 1 .. 4) {
            val b = at.below(dy - 1).below()
            if (lava(b)) return false
            if (solid(b)) return true
        }
        return false // nothing under us: we'd fall
    }
    val r = p.bbWidth / 2.0 - 0.01
    return safeFrom(0.0, 0.0) && safeFrom(r, r) && safeFrom(r, - r) && safeFrom(- r, r) && safeFrom(- r, - r)
}

/** Lava within 2 blocks around (and up to 2 below) the feet: then the Hyperion only ever goes straight down. */
internal fun AutoFishing.lavaAround(p: LocalPlayer): Boolean {
    val at = BlockPos.containing(p.position())
    for (dx in - 2 .. 2) for (dz in - 2 .. 2) for (dy in - 2 .. 0) if (isLava(at.offset(dx, dy, dz))) return true
    return false
}



/** "[TAG] name" of a player near us whose name carries a staff tag, or null. */
internal fun AutoFishing.staffNearby(p: LocalPlayer): String? {
    val tags = dev.riftwalk.utils.WatchSigns.parseTags(staffTags.value)
    val r = staffRange.value.toDouble()
    for (o in p.level().players()) {
        if (o === p || o.distanceToSqr(p) > r * r) continue
        val shown = o.displayName?.string ?: o.name.string
        val tag = dev.riftwalk.utils.WatchSigns.staffTag(shown, tags)
            ?: mc.connection?.getPlayerInfo(o.uuid)?.tabListDisplayName?.string?.let { dev.riftwalk.utils.WatchSigns.staffTag(it, tags) }
        if (tag != null) return "[$tag] ${o.name.string}"
    }
    // name tags floating over players (servers often draw ranks as a separate text)
    for ((_, text) in dev.riftwalk.utils.FloatingText.near(p.level(), p.boundingBox.inflate(r))) {
        val tag = dev.riftwalk.utils.WatchSigns.staffTag(text, tags) ?: continue
        if (Regex("\\[$tag\\+*] ?[A-Za-z0-9_]{1,16}", RegexOption.IGNORE_CASE).containsMatchIn(text.replace(Regex("§."), ""))) return text.replace(Regex("§."), "").take(40)
    }
    return null
}

//#endif
