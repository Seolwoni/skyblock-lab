package dev.marceline.ui.notification

import dev.marceline.Marceline.mc
import dev.marceline.event.EventBus
import dev.marceline.event.impl.ScreenEvent
import dev.marceline.init.types.ISelfInit
import dev.marceline.ui.clickgui.components.settings.Style
import dev.marceline.ui.utils.Animation
import dev.marceline.utils.ChatUtils.addColor
import dev.marceline.utils.ColorUtils.withAlpha
import dev.marceline.utils.render.Render2D.drawString
import dev.marceline.utils.render.UiFont
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Color
import java.util.concurrent.*

/**
 * Marceline toasts: slide in from the right, stack upwards and glide into place when one leaves,
 * a gradient bar shows the time left (hovering pauses it). Smooth font, at most 5 on screen.
 */
object NotificationManager: ISelfInit {
    private val notifications = CopyOnWriteArrayList<Notification>()
    private var lastFrameTime = System.currentTimeMillis()
    private val peek = Animation(160L, 1f) // 1 = shown, ~0.15 = stepped aside so the menu under it can be used

    private const val WIDTH = 190f
    private const val PAD = 9f
    private const val MAX_VISIBLE = 5
    private val bg = Color(22, 17, 19, 240)
    private val titleColor = Color(243, 234, 222)
    private val textColor = Color(206, 190, 180)
    /** Room the icon takes before the title and the text. */
    private const val ICON = 15f

    private fun iconTexture(i: NotificationIcon) =
        net.minecraft.resources.Identifier.fromNamespaceAndPath(dev.marceline.Marceline.MOD_ID, "textures/gui/notify/${i.texture}.png")

    /** Icons in the palette: warnings rose, rare finds and coins gold, the rest ivory. */
    private fun iconColor(i: NotificationIcon) = when (i) {
        NotificationIcon.WARN -> Color(232, 96, 112)
        NotificationIcon.STAR, NotificationIcon.COIN -> Style.gold
        NotificationIcon.CHECK -> Color(170, 204, 166)
        else -> Color(226, 206, 190)
    }

    fun push(title: String, message: String, duration: Long = 3000L) {
        val t = title.addColor()
        val m = message.addColor()
        if (notifications.any { it.title == t && it.message == m && it.duration == duration }) return
        mc.execute { notifications.add(Notification(t, m, duration, NotificationIcon.of(t, m))) }
    }

    override fun init() {
        EventBus.register<ScreenEvent.PostRender> { render(event.context) }
    }

    private fun smooth() = dev.marceline.features.impl.dev.ClickGui.customFont.value

    private fun width(s: String) = if (UiFont.active) UiFont.width(s) else mc.font.width(s.replace(Regex("§."), ""))

    private fun wrap(text: String, max: Float): List<String> {
        val out = ArrayList<String>()
        for (para in text.split('\n')) {
            var line = ""
            var carry = "" // colour codes carry over to the next line
            for (word in para.split(' ')) {
                val cand = if (line.isEmpty()) carry + word else "$line $word"
                if (width(cand) > max && line.isNotEmpty()) {
                    out += line
                    carry = Regex("§[0-9a-fk-or]").findAll(line).lastOrNull()?.value ?: ""
                    line = carry + word
                }
                else line = cand
            }
            if (line.isNotEmpty()) out += line
        }
        return out
    }

    @JvmStatic
    @JvmOverloads
    fun render(ctx: GuiGraphicsExtractor, w: Float? = null, h: Float? = null) {
        val now = System.currentTimeMillis()
        val delta = now - lastFrameTime
        lastFrameTime = now
        if (notifications.isEmpty()) return

        // same scale as the Marceline menu, so toasts match it whatever the GUI scale is
        dev.marceline.ui.utils.Resolution.push(ctx)
        val screenW = dev.marceline.ui.utils.Resolution.width
        val screenH = dev.marceline.ui.utils.Resolution.height
        val mX = dev.marceline.ui.utils.Resolution.getMouseX().toFloat()
        val mY = dev.marceline.ui.utils.Resolution.getMouseY().toFloat()

        // inside a menu, toasts become see-through while the mouse is over their corner
        val stackTop = screenH - notifications.size.coerceAtMost(MAX_VISIBLE) * 62f - 20f
        val near = mc.gui.screen() != null && mX >= screenW - WIDTH - 30f && mY >= stackTop
        peek.update(if (near) 0.15f else 1f)
        val pv = peek.value

        val prev = UiFont.active
        UiFont.active = smooth()
        try {
            var stackY = 0f
            var shown = 0
            for (n in notifications.reversed()) { // newest at the bottom
                if (n.isDead) {
                    notifications.remove(n)
                    continue
                }
                if (n.lines == null) n.lines = wrap(n.message, WIDTH - PAD * 2 - 8f - ICON)
                val lines = n.lines!!
                val height = PAD + 10f + lines.size * 10f + PAD + 2f

                val alive = n.elapsedTime < n.duration && shown < MAX_VISIBLE
                n.anim.update(if (alive) 1f else 0f)
                if (! alive && n.anim.value <= 0.01f) {
                    n.isDead = true
                    continue
                }
                shown++

                // slide in from the right (eased by Animation), glide to the stack position
                n.stack.update(stackY)
                val am = n.anim.value
                val x = screenW - (WIDTH + 10f) * am + (1f - am) * 12f
                val a = am * pv
                // on the title screen, stay clear of the version / copyright line along the bottom
                val bottom = if (mc.gui.screen() is net.minecraft.client.gui.screens.TitleScreen) 24f else 10f
                val y = screenH - height - bottom - n.stack.value

                val hovered = mX >= x && mX <= x + WIDTH && mY >= y && mY <= y + height
                if (! hovered && alive) n.elapsedTime += delta

                val alpha = (a * 255).toInt().coerceIn(0, 255)
                Style.drawShadow(ctx, x, y, WIDTH, height, 6f, 0.7f * a)
                Style.roundRect(ctx, x, y, WIDTH, height, 6f, bg.withAlpha((bg.alpha * a).toInt()))
                dev.marceline.ui.hud.HudCard.inner(ctx, x, y, WIDTH, height, 6f, a)
                // warm hairline edge, wine stripe on the left and a gold hairline along the top, like the menus
                Style.roundRect(ctx, x + 4f, y + PAD, 2f, height - PAD * 2 - 2f, 1f, Style.mix(Style.accentColor, Style.accent2, 0.3f).withAlpha(alpha))
                Style.goldHairline(ctx, x + 10f, y + 0.6f, WIDTH - 20f, a * 0.8f)

                // the icon in a small dark ring, left of the title
                val ix = x + PAD + 4f
                val iy = y + PAD - 1.5f
                Style.roundRect(ctx, ix - 1f, iy - 1f, 12f, 12f, 6f, iconColor(n.icon).withAlpha((70 * a).toInt()))
                Style.roundRect(ctx, ix - 0.25f, iy - 0.25f, 10.5f, 10.5f, 5.25f, Color(24, 12, 16, alpha))
                ctx.pose().pushMatrix()
                ctx.pose().translate(ix + 1.75f, iy + 1.75f)
                ctx.pose().scale(6.5f / 32f, 6.5f / 32f)
                with(dev.marceline.utils.render.Render2D) { ctx.drawTexture(iconTexture(n.icon), 0, 0, 32, 32, iconColor(n.icon).withAlpha(alpha)) }
                ctx.pose().popMatrix()

                val title = n.title.replace(Regex("§[0-9a-fk-or]"), "")
                if (title == "Marceline" && UiFont.active) {
                    // the brand name in the same wine -> rose letters as the menu logo
                    var tx = x + PAD + 5f + ICON
                    title.forEachIndexed { i, ch ->
                        ctx.drawString("§l$ch", tx, y + PAD, Style.mix(Color(179, 38, 62), Color(232, 80, 106), i / (title.length - 1f)).withAlpha(alpha), shadow = false)
                        tx += UiFont.width("§l$ch")
                    }
                }
                else ctx.drawString("§l$title", x + PAD + 5f + ICON, y + PAD, titleColor.withAlpha(alpha), shadow = false)
                var ly = y + PAD + 12f
                for (l in lines) {
                    // the game's bright magenta / aqua / yellow codes come out in the Marceline palette
                    Style.drawPaletteString(ctx, l, x + PAD + 5f + ICON, ly, textColor, { width(it).toFloat() }, alpha)
                    ly += 10f
                }

                // time left: a gradient pill that shrinks, paused while hovered
                val left = 1f - (n.elapsedTime.toFloat() / n.duration).coerceIn(0f, 1f)
                val bw = (WIDTH - 12f) * left
                val by = y + height - 4.5f
                Style.roundRect(ctx, x + 6f, by, WIDTH - 12f, 2f, 1f, Color(255, 255, 255, (14 * a).toInt()))
                if (bw > 2f) Style.gradientPill(ctx, x + 6f, by, bw, 2f, Style.accentColor.withAlpha((220 * a).toInt()), Style.accent2.withAlpha((220 * a).toInt()))

                stackY += (height + 6f) * am
            }
        } finally {
            UiFont.active = prev
            dev.marceline.ui.utils.Resolution.pop(ctx)
        }
    }

    private class Notification(val title: String, val message: String, val duration: Long, val icon: NotificationIcon) {
        val anim = Animation(380L)
        val stack = Animation(260L)
        var elapsedTime = 0L
        var isDead = false
        var lines: List<String>? = null
    }
}
