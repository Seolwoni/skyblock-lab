package dev.riftwalk.features.impl.misc.sound

import dev.riftwalk.config.types.SoundSetting
import dev.riftwalk.features.Feature
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundEvents

/**
 * @see dev.riftwalk.mixin.MixinSoundManager
 */
object ArrowHitSound: Feature() {
    private val soundConfig = createSoundSettings("Sound", SoundEvents.NOTE_BLOCK_HARP.value())

    @JvmStatic
    fun onSoundPlay(soundInstance: SoundInstance): Boolean {
        if (! enabled) return false
        if ((getSettingByName("Sound") as? SoundSetting)?.value?.location == soundInstance.identifier) return false
        if (soundInstance.identifier != SoundEvents.ARROW_HIT_PLAYER.location) return false
        soundConfig.invoke()
        return true
    }
}