package dev.marceline.utils

import net.minecraft.client.player.LocalPlayer

/**
 * Where the player looks, as the turning code sees it. [Humanize] writes the view only through this, so the turning
 * maths can run against a fake in unit tests ([TurnEngine]).
 */
interface ViewAccess {
    var yaw: Float
    var pitch: Float

    /** Both at once (one write, like a mouse move). */
    fun set(yaw: Float, pitch: Float) {
        this.yaw = yaw
        this.pitch = pitch
    }
}

/** The real player's view: writes go through PlayerUtils.rotate and keep the head turned with it. */
class PlayerView(private val p: LocalPlayer): ViewAccess {
    override var yaw: Float
        get() = p.yRot
        set(v) = set(v, p.xRot)

    override var pitch: Float
        get() = p.xRot
        set(v) = set(p.yRot, v)

    override fun set(yaw: Float, pitch: Float) {
        PlayerUtils.rotate(yaw, pitch)
        p.yHeadRot = p.yRot
    }
}
