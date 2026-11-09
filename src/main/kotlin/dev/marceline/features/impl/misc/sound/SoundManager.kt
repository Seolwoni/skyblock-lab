package dev.marceline.features.impl.misc.sound

import dev.marceline.config.PogObject
import dev.marceline.event.impl.WorldChangeEvent
import dev.marceline.features.Feature
import dev.marceline.features.IFeatureScreen
import dev.marceline.ui.gui.SoundManagerScreen
import dev.marceline.utils.SoundUtils
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import java.util.*
import kotlin.math.roundToInt

object SoundManager: Feature("Adjust volumes for every sound in the game"), IFeatureScreen {
    private val volumes = run {
        // the file used to be named after the old mod id; carry the saved volumes over once
        val dir = java.io.File("config/${dev.marceline.Marceline.MOD_NAME}")
        val old = java.io.File(dir, "noammaddons_sounds.json")
        val now = java.io.File(dir, "sounds.json")
        if (old.exists() && ! now.exists()) old.renameTo(now)
        PogObject("sounds", mutableMapOf<String, Float>())
    }
    private val recentSounds = Collections.synchronizedSet<Identifier>(mutableSetOf())
    @Volatile var recentSoundsVersion = 0L

    override fun createScreen() = SoundManagerScreen()

    override fun init() {
        volumes.get().replaceAll { _, multiplier -> normalizePercent((multiplier * 100f).roundToInt()) / 100f }

        register<WorldChangeEvent> { recentSounds.clear() }
    }

    @JvmStatic
    fun getMultiplier(id: String) = if (enabled) volumes.get().getOrDefault(id, 1f) else 1f

    @JvmStatic
    fun recordPlayedSound(sound: SoundInstance) {
        if (mc.gui.screen() is SoundManagerScreen) return
        recentSounds.add(sound.identifier)

        if (recentSounds.size > 100) recentSounds.remove(recentSounds.first())
        recentSoundsVersion ++
    }

    fun getVolumePercent(id: String) = (volumes.get().getOrDefault(id, 1f) * 100f).roundToInt()
    fun setVolumePercent(id: String, percent: Int) = volumes.get().set(id, normalizePercent(percent) / 100f)
    fun playPreview(sound: SoundEvent) {
        SoundUtils.playSound(sound, 0.25f, 1f)
    }

    fun getRecentSoundIds() = recentSounds.map(Identifier::toString).asReversed()

    private fun normalizePercent(percent: Int) = ((percent.toFloat() / 5).roundToInt() * 5).coerceIn(0, 200)
}