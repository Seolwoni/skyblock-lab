package dev.marceline.config.types

import dev.marceline.config.ConfigHolder
import dev.marceline.config.Savable
import dev.marceline.utils.GsonUtils.gsonObject
import com.google.gson.JsonElement

class MultiCheckboxSetting(name: String, defaultValue: MutableMap<String, Boolean>): ConfigHolder<MutableMap<String, Boolean>>(name, defaultValue.toMutableMap()), Savable {
    override fun write() = gsonObject { value.forEach(::addProperty) }
    override fun read(element: JsonElement) = element.asJsonObject.entrySet().forEach { (k, v) -> value[k] = v.asBoolean }

    operator fun get(key: String) = value[key] ?: error("[MultiCheckboxSetting] $key not found")
}