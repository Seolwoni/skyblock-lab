package human

import dev.marceline.utils.ViewAccess

/** A view for tests: remembers every write. */
class FakeView(yaw: Float = 0f, pitch: Float = 0f): ViewAccess {
    override var yaw = yaw
    override var pitch = pitch
    val writes = ArrayList<Pair<Float, Float>>()

    override fun set(yaw: Float, pitch: Float) {
        this.yaw = yaw
        this.pitch = pitch
        writes += yaw to pitch
    }
}
