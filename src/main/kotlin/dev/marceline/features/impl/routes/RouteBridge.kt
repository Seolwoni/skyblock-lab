package dev.marceline.features.impl.routes

import dev.marceline.features.impl.dungeon.Secrets
import xyz.yourboykyle.secretroutes.Main
import xyz.yourboykyle.secretroutes.dungeons.detection.DungeonScanner

/**
 * Where Marceline and Secret Routes do the same job, this decides who does it so it happens once:
 * - secret waypoints: the route leads while it has steps left in the room, then Noamm shows whatever secrets remain
 * - secret sound: one sound per secret (Noamm's if it is on, otherwise Secret Routes')
 */
object RouteBridge {
    /** A Secret Routes route is being followed in the room the player is standing in and still has steps left. */
    @JvmStatic
    fun routeActive(): Boolean {
        if (! SecretRoutes.enabled) return false
        val room = Main.currentRoom ?: return false
        val route = room.currentSecretRoute ?: return false
        return room.currentSecretIndex < route.size() && DungeonScanner.isPlayerInCurrentRoom()
    }

    /** Noamm's waypoints step aside while a route is running (when the hand-off setting is on). */
    @JvmStatic
    fun hideNoammSecretWaypoints() = SecretRoutes.handOff && routeActive()

    /** Noamm's secret sound is on, so Secret Routes stays quiet. */
    @JvmStatic
    fun noammPlaysSecretSound() = Secrets.enabled && Secrets.secretSound.value
}
