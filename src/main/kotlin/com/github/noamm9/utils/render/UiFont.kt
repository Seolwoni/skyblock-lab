package com.github.noamm9.utils.render

import com.github.noamm9.NoammAddons.MOD_ID
import com.github.noamm9.NoammAddons.mc
import net.minecraft.ChatFormatting
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

    private val REGULAR = FontDescription.Resource(Identifier.fromNamespaceAndPath(MOD_ID, "riftwalk"))
    private val BOLD = FontDescription.Resource(Identifier.fromNamespaceAndPath(MOD_ID, "riftwalk_bold"))

    private val cache = object: LinkedHashMap<String, Component>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Component>?) = size > 2048
    }

    /** [text] may contain § (or &) codes. */
    fun component(text: String): Component = cache.getOrPut(text) { build(text.replace('&', '§')) }

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
                val fmt = ChatFormatting.getByCode(text[i + 1].lowercaseChar())
                flush()
                when {
                    fmt == null -> {}
                    fmt == ChatFormatting.RESET -> { color = null; bold = false }
                    fmt == ChatFormatting.BOLD -> bold = true
                    fmt.isColor -> { color = fmt.color; bold = false }
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
