package dev.riftwalk.utils.location

import dev.riftwalk.event.EventBus
import dev.riftwalk.event.impl.ChatMessageEvent
import dev.riftwalk.event.impl.WorldChangeEvent
import dev.riftwalk.init.types.ISelfInit
import com.google.gson.JsonParser

object LocrawListener: ISelfInit {
    var server = ""
    var gameType = ""
    var location = ""
    var map = ""

    override fun init() {
        EventBus.register<ChatMessageEvent> {
            if (! LocationUtils.onHypixel) return@register
            parseLocRaw(event.unformattedText)
        }

        EventBus.register<WorldChangeEvent> { reset() }
    }

    private fun parseLocRaw(message: String) {
        if (! message.startsWith("{\"server\":") || ! message.endsWith("}")) return
        val locRaw = JsonParser.parseString(message).getAsJsonObject()
        if (locRaw.has("server")) server = locRaw.get("server").asString
        if (locRaw.has("gametype")) gameType = locRaw.get("gametype").asString
        if (locRaw.has("mode")) location = locRaw.get("mode").asString
        if (locRaw.has("map")) map = locRaw.get("map").asString
    }

    private fun reset() {
        server = ""
        gameType = ""
        location = ""
        map = ""
    }
}