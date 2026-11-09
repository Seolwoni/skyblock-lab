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

        val mod = parse("""{"text":"","extra":[{"text":"M","color":"#B3263E","bold":false,"shadow_color":[0.1,0.03,0.05,1]},{"text":"a","color":"#BA2B44","bold":false,"shadow_color":[0.1,0.03,0.05,1]},{"text":"r","color":"#C03049","bold":false,"shadow_color":[0.1,0.03,0.05,1]},{"text":"c","color":"#C7364E","bold":false,"shadow_color":[0.1,0.03,0.05,1]},{"text":"e","color":"#CE3B54","bold":false,"shadow_color":[0.1,0.03,0.05,1]},{"text":"l","color":"#D4405A","bold":false,"shadow_color":[0.1,0.03,0.05,1]},{"text":"i","color":"#DB465F","bold":false,"shadow_color":[0.1,0.03,0.05,1]},{"text":"n","color":"#E14B64","bold":false,"shadow_color":[0.1,0.03,0.05,1]},{"text":"e","color":"#E8506A","bold":false,"shadow_color":[0.1,0.03,0.05,1]}]}""") !!
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