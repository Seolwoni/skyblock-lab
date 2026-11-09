package dev.marceline.features.impl.dev.text

import dev.marceline.Marceline.MOD_NAME
import dev.marceline.utils.catch
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

        val mod = parse("""{"text":"","extra":[{"text":"M","color":"#B3263E","bold":true,"shadow_color":[0.39,0.08,0.13,1]},{"text":"a","color":"#BA2B44","bold":true,"shadow_color":[0.4,0.09,0.15,1]},{"text":"r","color":"#C03049","bold":true,"shadow_color":[0.41,0.1,0.16,1]},{"text":"c","color":"#C7364E","bold":true,"shadow_color":[0.43,0.12,0.17,1]},{"text":"e","color":"#CE3B54","bold":true,"shadow_color":[0.44,0.13,0.18,1]},{"text":"l","color":"#D4405A","bold":true,"shadow_color":[0.46,0.14,0.19,1]},{"text":"i","color":"#DB465F","bold":true,"shadow_color":[0.47,0.15,0.2,1]},{"text":"n","color":"#E14B64","bold":true,"shadow_color":[0.49,0.16,0.22,1]},{"text":"e","color":"#E8506A","bold":true,"shadow_color":[0.5,0.17,0.23,1]}]}""") !!
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