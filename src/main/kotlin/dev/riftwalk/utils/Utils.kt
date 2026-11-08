package dev.riftwalk.utils

import dev.riftwalk.Riftwalk.mc
import dev.riftwalk.compat.openUri
import net.minecraft.network.protocol.Packet
import java.awt.Color
import java.net.URI

object Utils {
    val favoriteColor = Color(167, 107, 250) // Void Rift violet (was Noamm blue)

    fun openDiscordLink() = openUri(URI("https://discord.gg/pj9mQGxMxB"))

    fun Packet<*>.send() = mc.connection?.send(this)
}