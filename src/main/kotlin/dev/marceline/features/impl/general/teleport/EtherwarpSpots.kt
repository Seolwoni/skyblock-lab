package dev.marceline.features.impl.general.teleport

import dev.marceline.Marceline.mc
import dev.marceline.config.types.SliderSetting
import dev.marceline.config.types.ToggleSetting
import dev.marceline.event.impl.RenderWorldEvent
import dev.marceline.event.impl.TickEvent
import dev.marceline.features.Feature
import dev.marceline.features.impl.fishing.TravelMath
import dev.marceline.features.impl.fishing.TravelMath.Why
import dev.marceline.utils.PathMath.P
import dev.marceline.utils.SkyblockTravel
import dev.marceline.utils.items.EtherwarpHelper
import dev.marceline.utils.items.ItemUtils.skyblockId
import dev.marceline.utils.render.world.Render3D.renderBoxBounds
import dev.marceline.utils.render.world.Render3D.renderString
import net.minecraft.core.BlockPos
import java.awt.Color
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Shows where an etherwarp could land around where you look: green spots work, red ones don't (and why), the closest
 * working one gets a bold outline and its distance. Uses the same landing check as Auto Fishing's teleport travel.
 * Only shows something; it never moves your view.
 */
object EtherwarpSpots: Feature("Shows where an etherwarp can land around your crosshair: green works, red doesn't (with the reason), the closest working spot gets its distance. Only while holding an AOTV with Etherwarp and with soulflow.") {
    private val maxRange by SliderSetting("Max Range", 61, 10, 61, 1, " blocks")
    private val opacity by SliderSetting("Opacity", 0.35, 0.05, 1.0, 0.05)
    private val showInvalid by ToggleSetting("Show Invalid Spots", true).withDescription("Also draws the spots that don't work, with why")
    private val textSize by SliderSetting("Text Size", 1.0, 0.5, 2.0, 0.1, "x")

    private val GREEN = Color(80, 230, 120)
    private val RED = Color(235, 80, 80)
    private const val FADE_MS = 300L
    private const val EVERY_TICKS = 4 // 5 times a second

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "Marceline etherwarp spots").apply { isDaemon = true } }
    private val latest = AtomicReference<List<TravelMath.Candidate>>(emptyList())
    private val busy = AtomicBoolean(false)
    private var ticks = 0
    private var shown = false

    private var nearest: P? = null
    private var nearestAt = 0L
    private var previous: P? = null

    private fun bp(p: P) = BlockPos(p.x, p.y, p.z)

    private val blocks = object : TravelMath.Blocks {
        override fun solid(p: P): Boolean {
            val l = mc.level ?: return false
            val b = bp(p)
            return ! l.getBlockState(b).getCollisionShape(l, b).isEmpty && l.getFluidState(b).isEmpty
        }
        override fun passable(p: P): Boolean {
            val l = mc.level ?: return false
            val b = bp(p)
            return l.getBlockState(b).getCollisionShape(l, b).isEmpty && l.getFluidState(b).isEmpty
        }
        override fun lava(p: P) = mc.level?.getFluidState(bp(p))?.`is`(net.minecraft.tags.FluidTags.LAVA) == true
        override fun inWorld(y: Int) = mc.level?.isOutsideBuildHeight(y) == false
    }

    private fun label(w: Why) = when (w) {
        Why.SPACE -> "공간 부족"
        Why.LAVA -> "용암"
        Why.RANGE -> "사거리 밖"
        Why.WORLD -> "월드 경계"
        Why.OK -> ""
    }

    /** Holding an AOTV with Etherwarp, soulflow shown and above 0, no screen open. */
    private fun active(): Boolean {
        val p = mc.player ?: return false
        if (mc.gui.screen() != null) return false
        val held = p.mainHandItem
        if (held.skyblockId != SkyblockTravel.AOTV || EtherwarpHelper.getEtherwarpDistance(held) == null) return false
        val soul = runCatching { SkyblockTravel.soulflowFrom(dev.marceline.utils.TabListUtils.getTabList().asSequence().map { it.first.string }) }.getOrNull()
        return soul != null && soul >= SkyblockTravel.ETHERWARP_SOULFLOW
    }

    override fun init() {
        register<TickEvent.End> {
            shown = active()
            if (! shown) { latest.set(emptyList()); return@register }
            if (++ticks % EVERY_TICKS != 0 || ! busy.compareAndSet(false, true)) return@register
            val p = mc.player ?: run { busy.set(false); return@register }
            // eye at sneaking height (etherwarp is used sneaking)
            val ex = p.x; val ey = p.y + 1.27; val ez = p.z
            val yaw = p.yRot.toDouble(); val pitch = p.xRot.toDouble()
            val range = maxRange.value
            worker.execute {
                try {
                    latest.set(runCatching { TravelMath.etherwarpCandidates(ex, ey, ez, yaw, pitch, range, blocks) }.getOrDefault(emptyList()))
                } finally { busy.set(false) }
            }
        }

        register<RenderWorldEvent> {
            if (! shown) return@register
            val list = latest.get()
            val now = System.currentTimeMillis()
            val best = TravelMath.nearestValid(list)?.feet
            if (best != nearest) { previous = nearest; nearest = best; nearestAt = now }
            val fadeIn = TravelMath.fade(now - nearestAt, FADE_MS)
            val alpha = opacity.value.toFloat()
            val ts = textSize.value.toFloat()
            for (c in list) {
                if (c.why != Why.OK && ! showInvalid.value) continue
                val base = if (c.why == Why.OK) GREEN else RED
                // the new closest spot fades in, the old one fades back to normal
                val weight = when (c.feet) { nearest -> 0.5f + 0.5f * fadeIn; previous -> 1f - 0.5f * fadeIn; else -> 0.5f }
                val a = (alpha * weight * 255).toInt().coerceIn(8, 255)
                val outline = Color(base.red, base.green, base.blue, (a * 2).coerceAtMost(255))
                val fill = Color(base.red, base.green, base.blue, (a / 3).coerceAtLeast(4))
                val f = c.feet
                val bold = c.feet == nearest
                event.ctx.renderBoxBounds(f.x.toDouble(), f.y - 1.0, f.z.toDouble(), f.x + 1.0, f.y.toDouble(), f.z + 1.0, outline, fill, outline = true, fill = true, phase = true, lineWidth = if (bold) 2.5 + 2.5 * fadeIn else 1.5)
                if (bold) event.ctx.renderString("%.1fm".format(c.dist), f.x + 0.5, f.y + 0.6, f.z + 0.5, Color(255, 255, 255, (255 * fadeIn).toInt().coerceAtLeast(30)), ts, phase = true)
                else if (c.why != Why.OK) event.ctx.renderString(label(c.why), f.x + 0.5, f.y + 0.4, f.z + 0.5, Color(255, 190, 190), ts * 0.8f, phase = true)
            }
        }
    }
}
