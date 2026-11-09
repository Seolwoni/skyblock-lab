package dev.marceline.features.impl.misc

import dev.marceline.Marceline.mc
import dev.marceline.config.types.KeybindSetting
import dev.marceline.config.types.SliderSetting
import dev.marceline.config.types.ToggleSetting
import dev.marceline.event.impl.TickEvent
import dev.marceline.features.Feature
import net.minecraft.client.CameraType

/**
 * Free Look: while the key is held (or toggled), the mouse turns the camera only; your character keeps looking
 * where it was (and an automatic feature keeps aiming as it likes). Third person by default.
 */
object FreeLook: Feature("Hold a key to look around without turning your character: the mouse moves only the camera (third person by default). Handy for watching around while Auto Fishing or a route keeps its own aim.") {
    private val key by KeybindSetting("Free Look Key")
    private val toggleMode by ToggleSetting("Toggle Instead Of Hold", false).withDescription("Press once to start, again to stop")
    private val thirdPerson by ToggleSetting("Third Person", true).withDescription("Switch to the view behind you while looking around (back to your view after)")
    private val speed by SliderSetting("Speed", 1.0, 0.3, 2.0, 0.05, "x").withDescription("Camera speed compared to normal mouse turning")
    private val invertY by ToggleSetting("Invert Up / Down", false)

    @JvmField var active = false
    @JvmField var yaw = 0f
    @JvmField var pitch = 0f
    /** Set by the camera while it lines up with its entity, so the view angles it reads are ours. */
    @JvmField var aligning = false

    private var previous: CameraType? = null
    private var toggled = false

    override fun init() {
        register<TickEvent.Start> {
            val p = mc.player
            if (p == null) { if (active) end(); return@register }
            if (toggleMode.value && key.isPressed() && mc.gui.screen() == null) toggled = ! toggled
            val want = enabled && (if (toggleMode.value) toggled else key.isDown())
            if (want && ! active) begin(p.yRot, p.xRot)
            if (! want && active) end()
        }
    }

    override fun onDisable() {
        super.onDisable()
        toggled = false
        if (active) end()
    }

    private fun begin(y: Float, x: Float) {
        yaw = y
        pitch = x
        active = true
        if (thirdPerson.value) {
            previous = mc.options.cameraType
            mc.options.cameraType = CameraType.THIRD_PERSON_BACK
        }
    }

    private fun end() {
        active = false
        previous?.let { mc.options.cameraType = it }
        previous = null
    }

    /** Mouse turning while active goes to the camera (true = handled, the character doesn't turn). */
    @JvmStatic
    fun turn(dx: Double, dy: Double): Boolean {
        if (! active) return false
        val s = speed.value.toFloat()
        yaw += (dx * 0.15).toFloat() * s
        pitch = (pitch + (dy * 0.15).toFloat() * s * (if (invertY.value) - 1f else 1f)).coerceIn(- 90f, 90f)
        return true
    }
}
