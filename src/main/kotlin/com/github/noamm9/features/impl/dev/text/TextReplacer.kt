package com.github.noamm9.features.impl.dev.text

import com.github.noamm9.NoammAddons.MOD_NAME
import com.github.noamm9.utils.catch
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentSerialization

object TextReplacer: AhoCorasick() {
    @JvmField @Volatile var ready = false
    @JvmField var drawingTooltip = false

    fun init(names: Map<String, String>) {
        clear()

        for ((k, v) in names) {
            val parsed = parse(v) ?: continue
            put(k, parsed.string, parsed, parsed.visualOrderText)
        }

        val mod = parse("""{"text":"","extra":[{"text":"R","color":"#7B5CE0","bold":true,"shadow_color":[0.29,0.22,0.53,1]},{"text":"i","color":"#8A57D5","bold":true,"shadow_color":[0.32,0.2,0.5,1]},{"text":"f","color":"#9952CB","bold":true,"shadow_color":[0.36,0.19,0.48,1]},{"text":"t","color":"#A84DC0","bold":true,"shadow_color":[0.4,0.18,0.45,1]},{"text":"w","color":"#B749B6","bold":true,"shadow_color":[0.43,0.17,0.43,1]},{"text":"a","color":"#C644AB","bold":true,"shadow_color":[0.47,0.16,0.4,1]},{"text":"l","color":"#D53FA1","bold":true,"shadow_color":[0.5,0.15,0.38,1]},{"text":"k","color":"#E43A96","bold":true,"shadow_color":[0.54,0.14,0.35,1]}]}""") !!
        put(MOD_NAME, mod.string, mod, mod.visualOrderText)

        build()
        ready = true
    }

    private fun parse(json: String): Component? {
        if (! json.trimStart().startsWith("{") && ! json.trimStart().startsWith("[")) return null

        return catch {
            val jsonElement = JsonParser.parseString(json)
            val result = ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, jsonElement)
            result.result().orElse(null)
        }
    }
}