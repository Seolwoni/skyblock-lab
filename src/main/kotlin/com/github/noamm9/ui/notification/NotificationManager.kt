package com.github.noamm9.ui.notification

import com.github.noamm9.NoammAddons.mc
import com.github.noamm9.event.EventBus
import com.github.noamm9.event.impl.ScreenEvent
import com.github.noamm9.init.types.ISelfInit
import com.github.noamm9.ui.clickgui.components.settings.Style
import com.github.noamm9.ui.utils.Animation
import com.github.noamm9.utils.ChatUtils.addColor
import com.github.noamm9.utils.ColorUtils.withAlpha
import com.github.noamm9.utils.render.Render2D.drawString
import com.github.noamm9.utils.render.UiFont
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.awt.Color
import java.util.concurrent.*

/**
 * Void Rift toasts: slide in from the right, stack upwards and glide into place when one leaves,
 * a gradient bar shows the time left (hovering pauses it). Smooth font, at most 5 on screen.
 */
object NotificationManager: ISelfInit {
    private val notifications = CopyOnWriteArrayList<Notification>()
    private var lastFrameTime = System.currentTimeMillis()
    private val peek = Animation(160L, 1f) // 1 = shown, ~0.15 = stepped aside so the menu under it can be used

    private const val WIDTH = 190f
    private const val PAD = 9f
    private const val MAX_VISIBLE = 5
    private val bg = Color(21, 15, 32, 240)
    private val titleColor = Color(237, 231, 255)
    private val textColor = Color(176, 164, 204)

    fun push(title: String, message: String, duration: Long = 3000L) {
        val t = title.addColor()
        val m = message.addColor()
        if (notifications.any { it.title == t && it.message == m && it.duration == duration }) return
        mc.execute { notifications.add(Notification(t, m, duration)) }
    }

    override fun init() {
        EventBus.register<ScreenEvent.PostRender> { render(event.context) }
    }

    private fun smooth() = com.github.noamm9.features.impl.dev.ClickGui.customFont.value

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

        // same scale as the Riftwalk menu, so toasts match it whatever the GUI scale is
        com.github.noamm9.ui.utils.Resolution.push(ctx)
        val screenW = com.github.noamm9.ui.utils.Resolution.width
        val screenH = com.github.noamm9.ui.utils.Resolution.height
        val mX = com.github.noamm9.ui.utils.Resolution.getMouseX().toFloat()
        val mY = com.github.noamm9.ui.utils.Resolution.getMouseY().toFloat()

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
                if (n.lines == null) n.lines = wrap(n.message, WIDTH - PAD * 2 - 8f)
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
                val y = screenH - height - 10f - n.stack.value

                val hovered = mX >= x && mX <= x + WIDTH && mY >= y && mY <= y + height
                if (! hovered && alive) n.elapsedTime += delta

                val alpha = (a * 255).toInt().coerceIn(0, 255)
                Style.drawShadow(ctx, x, y, WIDTH, height, 6f, 0.7f * a)
                Style.roundRect(ctx, x, y, WIDTH, height, 6f, bg.withAlpha((bg.alpha * a).toInt()))
                // accent stripe on the left and a top highlight, like light on glass
                Style.roundRect(ctx, x + 4f, y + PAD, 2f, height - PAD * 2 - 2f, 1f, Style.mix(Style.accentColor, Style.accent2, 0.3f).withAlpha(alpha))
                Style.roundRect(ctx, x + 12f, y + 1f, WIDTH - 24f, 1f, 0.5f, Color(255, 255, 255, (16 * a).toInt()))

                val title = n.title.replace(Regex("§[0-9a-fk-or]"), "")
                if (title == "Riftwalk" && UiFont.active) {
                    // the brand name in the same purple -> pink letters as the menu logo
                    var tx = x + PAD + 5f
                    title.forEachIndexed { i, ch ->
                        ctx.drawString("§l$ch", tx, y + PAD, Style.mix(Color(139, 92, 246), Color(236, 72, 153), i / (title.length - 1f)).withAlpha(alpha), shadow = false)
                        tx += UiFont.width("§l$ch")
                    }
                }
                else ctx.drawString("§l$title", x + PAD + 5f, y + PAD, titleColor.withAlpha(alpha), shadow = false)
                var ly = y + PAD + 12f
                for (l in lines) {
                    ctx.drawString(l, x + PAD + 5f, ly, textColor.withAlpha(alpha), shadow = false)
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
            com.github.noamm9.ui.utils.Resolution.pop(ctx)
        }
    }

    private class Notification(val title: String, val message: String, val duration: Long) {
        val anim = Animation(380L)
        val stack = Animation(260L)
        var elapsedTime = 0L
        var isDead = false
        var lines: List<String>? = null
    }
}
