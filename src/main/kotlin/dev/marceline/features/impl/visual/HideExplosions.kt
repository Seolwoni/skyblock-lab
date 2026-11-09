package dev.marceline.features.impl.visual

import dev.marceline.config.types.ToggleSetting
import dev.marceline.event.impl.MainThreadPacketReceivedEvent
import dev.marceline.features.Feature
import dev.marceline.utils.equalsOneOf
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.sounds.SoundEvents

/** The Hyperion's Wither Impact (and any other blast) without the explosion cloud, and optionally without its bang. */
object HideExplosions: Feature("Hides explosion particles, such as the Hyperion's Wither Impact blast.", toggled = true) {
    private val particles by ToggleSetting("Hide Particles", true).withDescription("The explosion cloud")
    private val sound by ToggleSetting("Mute Sound", false).withDescription("The explosion bang as well")

    override fun init() {
        register<MainThreadPacketReceivedEvent.Pre> {
            when (val packet = event.packet) {
                is ClientboundLevelParticlesPacket ->
                    if (particles.value && packet.particle.type.equalsOneOf(ParticleTypes.EXPLOSION, ParticleTypes.EXPLOSION_EMITTER)) event.isCanceled = true
                is ClientboundSoundPacket ->
                    if (sound.value && SoundEvents.GENERIC_EXPLODE.`is`(packet.sound.value().location)) event.isCanceled = true
            }
        }
    }
}
