package com.github.noamm9.utils

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style

/**
 * Void Rift chat look: a purple -> pink [RW] prefix and a softer palette for the message body.
 * Legacy § colours are mapped onto theme colours, so "§a" is still green and "§c" still red,
 * just in tones that sit well next to the prefix.
 */
object RiftChat {
    const val BASE = 0xE9E3F7 // body text (instead of plain white)

    private val palette = mapOf(
        '0' to 0x1A1426, '1' to 0x6366F1, '2' to 0x34D399, '3' to 0x8B5CF6,
        '4' to 0xE11D48, '5' to 0xA855F7, '6' to 0xFBBF24, '7' to 0xA79BC4,
        '8' to 0x6B5F86, '9' to 0x818CF8, 'a' to 0x86EFAC, 'b' to 0xC4B5FD,
        'c' to 0xFB7185, 'd' to 0xF472B6, 'e' to 0xFDE68A, 'f' to BASE,
    )

    fun colorOf(code: Char): Int? = palette[code.lowercaseChar()]

    /** "[RW] ›" with every letter stepping from violet to pink. */
    val prefix: MutableComponent by lazy {
        Component.empty().apply {
            append(part("[", 0x6D4AC2, true))
            append(part("R", 0x8B5CF6, true))
            append(part("W", 0xEC4899, true))
            append(part("]", 0xB0397A, true))
        }
    }

    private fun part(s: String, rgb: Int, bold: Boolean) =
        Component.literal(s).withStyle { it.withColor(rgb).withBold(bold) }

    /** Turns a message with § codes into a component in the theme palette. */
    fun body(text: String): MutableComponent {
        val root = Component.empty()
        var style = Style.EMPTY.withColor(BASE)
        val buf = StringBuilder()
        fun flush() {
            if (buf.isEmpty()) return
            root.append(Component.literal(buf.toString()).withStyle(style))
            buf.setLength(0)
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '§' && i + 1 < text.length) {
                val code = text[i + 1].lowercaseChar()
                flush()
                val rgb = palette[code]
                style = when {
                    rgb != null -> Style.EMPTY.withColor(rgb)
                    code == 'l' -> style.withBold(true)
                    code == 'o' -> style.withItalic(true)
                    code == 'n' -> style.withUnderlined(true)
                    code == 'm' -> style.withStrikethrough(true)
                    code == 'k' -> style.withObfuscated(true)
                    code == 'r' -> Style.EMPTY.withColor(BASE)
                    else -> style
                }
                i += 2
                continue
            }
            buf.append(c)
            i ++
        }
        flush()
        return root
    }
}
