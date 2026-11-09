package dev.marceline.utils

import dev.marceline.Marceline.mc
import dev.marceline.compat.openUri
import net.minecraft.network.protocol.Packet
import java.awt.Color
import java.net.URI

object Utils {
    val favoriteColor = Color(214, 64, 88) // Marceline dark-red (was Noamm blue)


    fun Packet<*>.send() = mc.connection?.send(this)
}