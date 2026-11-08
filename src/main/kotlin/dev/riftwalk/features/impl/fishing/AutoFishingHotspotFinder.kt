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

// Finding Hotspots out of sight: shared positions from chat, and the Hotspot Radar (two readings from two places
// cross where the hotspot is). Name tags in sight are still the first choice (AutoFishingTravel).

/** "...hotspot... 123, 70, -45" / "x: 123 y: 70 z: -45" in chat (party / all / a mod's share message). */
internal fun AutoFishing.readSharedHotspot(text: String) {
    val t = text.replace(Regex("§."), "")
    if (! t.contains("hotspot", ignoreCase = true)) return
    val m = Regex("(?:x[:=]?\\s*)?(-?\\d{1,5})[ ,]+(?:y[:=]?\\s*)?(-?\\d{1,3})[ ,]+(?:z[:=]?\\s*)?(-?\\d{1,5})", RegexOption.IGNORE_CASE).find(t) ?: return
    val (x, y, z) = m.destructured
    val pos = Vec3(x.toDouble() + 0.5, y.toDouble(), z.toDouble() + 0.5)
    remembered[BlockPos.containing(pos).asLong()] = pos to System.currentTimeMillis()
    info("&7Hotspot shared in chat at &f${x}, ${y}, ${z}&7 - remembered.")
}

internal fun AutoFishing.radarSlot(): Int? = PlayerUtils.findHotbarSlot {
    it.skyblockId.contains(dev.riftwalk.utils.SkyblockText.HOTSPOT_RADAR) || it.hoverName.string.contains(dev.riftwalk.utils.SkyblockText.HOTSPOT_RADAR_NAME, ignoreCase = true)
}

/** A particle while the radar is reading: keep the ones around us (the trail starts at us and points the way). */
internal fun AutoFishing.radarParticle(x: Double, y: Double, z: Double) {
    if (radarStage != 1) return
    val o = radarOrigin ?: return
    val d = kotlin.math.sqrt((x - o.x) * (x - o.x) + (z - o.z) * (z - o.z))
    if (d in 1.0 .. 25.0) radarPts += Vec3(x, y, z)
}

/**
 * One step of a radar reading. Returns true while it's busy (caller waits).
 * Stage 0: take the radar out and use it. Stage 1: collect the trail for 1.5 s, then work out its direction.
 * With two readings from places 12+ blocks apart and 15+ degrees different, the lines cross at the hotspot;
 * with one, the hotspot is somewhere ahead - go 35 blocks that way and read again.
 */
internal fun AutoFishing.radarStep(p: LocalPlayer): Boolean {
    if (! useRadar.value) return false
    val slot = radarSlot() ?: return false
    when (radarStage) {
        0 -> {
            if (radarCooldown > 0) { radarCooldown--; return false }
            hold(slot)
            radarOrigin = p.position()
            radarPts.clear()
            use(p)
            radarStage = 1
            radarTicks = 0
            status = "reading the Hotspot Radar"
            return true
        }
        1 -> {
            if (++radarTicks < 30) return true
            radarStage = 0
            radarCooldown = 100
            rod()?.let { hold(it) }
            val o = radarOrigin ?: return false
            val dir = radarDirection(o) ?: run { info("&7The radar showed nothing readable."); return false }
            // keep the latest readings taken from different places
            radarLines.removeIf { it.first.distanceTo(o) < 12.0 }
            radarLines += o to dir
            while (radarLines.size > 3) radarLines.removeAt(0)
            val guess = radarCross() ?: o.add(dir.x * 35.0, 0.0, dir.z * 35.0)
            val crossed = radarLines.size >= 2 && radarCross() != null
            remembered[BlockPos.containing(guess).asLong()] = guess to System.currentTimeMillis()
            radarGuess = guess
            info(if (crossed) "&7Radar: hotspot about &f${p.position().distanceTo(guess).toInt()}&7 blocks away." else "&7Radar: hotspot somewhere that way, going to read again.")
            return true // the guess is picked up as a remembered hotspot next tick
        }
    }
    return false
}

/** The trail's direction (flat): the most common bearing of the particles, averaged around it. */
internal fun AutoFishing.radarDirection(o: Vec3): Vec3? {
    if (radarPts.size < 5) return null
    val bearings = radarPts.map { Math.toDegrees(kotlin.math.atan2(it.z - o.z, it.x - o.x)) }
    val bins = IntArray(36)
    for (b in bearings) bins[(((b + 360) % 360) / 10).toInt().coerceIn(0, 35)]++
    val mode = bins.indices.maxBy { bins[it] } * 10.0 + 5.0
    val near = radarPts.filter {
        val b = Math.toDegrees(kotlin.math.atan2(it.z - o.z, it.x - o.x))
        abs(((b - mode + 540) % 360) - 180) <= 15
    }
    if (near.size < 4) return null
    var x = 0.0; var z = 0.0
    for (pt in near) { val l = kotlin.math.hypot(pt.x - o.x, pt.z - o.z); x += (pt.x - o.x) / l; z += (pt.z - o.z) / l }
    val l = kotlin.math.hypot(x, z)
    return Vec3(x / l, 0.0, z / l)
}

/** Where the last two readings' lines cross (only if they're far enough apart to trust it). */
internal fun AutoFishing.radarCross(): Vec3? {
    if (radarLines.size < 2) return null
    val (a, da) = radarLines[radarLines.size - 2]
    val (b, db) = radarLines.last()
    val c = FishingMath.cross(a.x, a.z, da.x, da.z, b.x, b.z, db.x, db.z) ?: return null // parallel or behind
    return Vec3(c[0], (a.y + b.y) / 2, c[1])
}

/** Somewhere to stand near a guessed point (it may be over water / lava or a little off). */
internal fun AutoFishing.groundNear(c: Vec3): Vec3? {
    val base = BlockPos.containing(c)
    var best: BlockPos? = null
    var bd = Double.MAX_VALUE
    for (dx in - 8 .. 8) for (dz in - 8 .. 8) for (dy in 12 downTo - 12) {
        val f = base.offset(dx, dy, dz)
        if (! standable(f) || nearLava(f, 1)) continue
        val d = Vec3.atBottomCenterOf(f).distanceToSqr(c)
        if (d < bd) { bd = d; best = f }
        break
    }
    return best?.let { Vec3.atBottomCenterOf(it) }
}
//#endif
