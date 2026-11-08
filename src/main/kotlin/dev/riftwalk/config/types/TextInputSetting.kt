package dev.riftwalk.config.types

import dev.riftwalk.config.ConfigHolder
import dev.riftwalk.config.Savable
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive

class TextInputSetting(name: String, defaultValue: String): ConfigHolder<String>(name, defaultValue), Savable {
    override fun write() = JsonPrimitive(value)
    override fun read(element: JsonElement) {
        value = element.asString
    }
}