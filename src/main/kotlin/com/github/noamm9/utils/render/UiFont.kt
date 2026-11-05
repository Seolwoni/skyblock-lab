package com.github.noamm9.utils.render

import com.github.noamm9.NoammAddons.MOD_ID
import com.github.noamm9.NoammAddons.mc
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.MutableComponent
import net.minecraft.resources.Identifier

/**
 * Riftwalk's menu font (Pretendard, OFL). While [active] is true, Render2D text and width helpers
 * draw with it, so everything inside the Riftwalk screen - including the shared setting widgets -
 * uses the smooth font, and chat / HUDs keep Minecraft's own.
 * §-codes are turned into styles here so colours and bold survive (bold uses the SemiBold file).
 */
object UiFont {
    @JvmField var active = false
    /** Use the Void Rift palette for § colours (HUDs) instead of Minecraft's own. */
    @JvmField var themed = false

    /** Minecraft's 16 chat colours by code. */
    private val COLORS = mapOf(
        '0' to 0x000000, '1' to 0x0000AA, '2' to 0x00AA00, '3' to 0x00AAAA, '4' to 0xAA0000, '5' to 0xAA00AA, '6' to 0xFFAA00, '7' to 0xAAAAAA,
        '8' to 0x555555, '9' to 0x5555FF, 'a' to 0x55FF55, 'b' to 0x55FFFF, 'c' to 0xFF5555, 'd' to 0xFF55FF, 'e' to 0xFFFF55, 'f' to 0xFFFFFF
    )

    private val REGULAR = FontDescription.Resource(Identifier.fromNamespaceAndPath(MOD_ID, "riftwalk"))
    /** The menu font, for chat components. */
    val regular: FontDescription get() = REGULAR
    private val BOLD = FontDescription.Resource(Identifier.fromNamespaceAndPath(MOD_ID, "riftwalk_bold"))

    private val cache = object: LinkedHashMap<String, Component>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Component>?) = size > 2048
    }

    /** [text] may contain § (or &) codes. */
    fun component(text: String): Component = cache.getOrPut(if (themed) "\u0000$text" else text) { build(text.replace('&', '§')) }

    fun width(text: String): Int = mc.font.width(component(text))

    private fun build(text: String): Component {
        val root: MutableComponent = Component.empty()
        var color: Int? = null
        var bold = false
        val part = StringBuilder()

        fun flush() {
            if (part.isEmpty()) return
            val c = color
            val font = if (bold) BOLD else REGULAR
            root.append(Component.literal(part.toString()).withStyle { s ->
                val styled = s.withFont(font)
                if (c != null) styled.withColor(c) else styled
            })
            part.setLength(0)
        }

        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '§' && i + 1 < text.length) {
                val code = text[i + 1].lowercaseChar()
                flush()
                when (code) {
                    'r' -> { color = null; bold = false }
                    'l' -> bold = true
                    else -> (if (themed) com.github.noamm9.utils.RiftChat.colorOf(code) else COLORS[code])?.let { color = it; bold = false }
                }
                i += 2
                continue
            }
            part.append(ch)
            i++
        }
        flush()
        return root
    }
}
