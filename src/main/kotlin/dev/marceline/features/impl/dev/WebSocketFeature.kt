package dev.marceline.features.impl.dev

import dev.marceline.Marceline
import dev.marceline.commands.CommandBuilder
import dev.marceline.config.types.ToggleSetting
import dev.marceline.event.impl.*
import dev.marceline.event.priority.EventPriority
import dev.marceline.features.Feature
import dev.marceline.init.types.ICommandProvider
import dev.marceline.utils.*
import dev.marceline.utils.ChatUtils.addColor
import dev.marceline.utils.dungeons.DungeonListener
import dev.marceline.utils.dungeons.map.core.RoomTile
import dev.marceline.utils.dungeons.map.core.RoomType
import dev.marceline.utils.dungeons.map.handlers.DungeonScanner
import dev.marceline.utils.location.LocationUtils
import dev.marceline.utils.location.LocrawListener
import dev.marceline.websocket.PacketRegistry
import dev.marceline.websocket.WebSocket
import dev.marceline.websocket.WebSocket.send
import dev.marceline.websocket.packets.C2SPacketDungeonStart
import dev.marceline.websocket.packets.S2CPacketChat
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.mojang.brigadier.arguments.StringArgumentType

object WebSocketFeature: Feature("Shared party chat and dungeon events through the NoammAddons WebSocket server (ws.noamm.org). Off unless you turn it on; takes effect after a restart.", name = "WebSocket"), ICommandProvider {
    private val mutedPartyChat by ToggleSetting("Muted Party Chat", true).withDescription("Sends party messages through the WebSocket when Hypixel chat is muted during a dungeon.")
    private val partyChatPrefixes = listOf("pc ", "party chat ", "p chat ")
    @Volatile private var pendingChatMessage: String? = null

    override fun init() {
        register<WebSocketEvent.Connect> {
            Marceline.logger.debug("WebSocket: Connected Successfully")
            ChatUtils.debug("ws", "[WS] Connected Successfully")
        }

        register<WebSocketEvent.Payload> {
            ChatUtils.debug("ws", "[WS] Received payload: ${event.message}")
            val json = JsonParser.parseString(event.message).takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@register
            val type = json.get("type").asString.takeUnless(String::isBlank) ?: return@register
            val packetClass = PacketRegistry.getClass(type) ?: return@register
            GsonUtils.gson.fromJson(json, packetClass).handle()
        }

        register<DungeonEvent.RunStartedEvent> { sendDungeonInfo() }
        register<DungeonEvent.RunEndedEvent> { send(mapOf("type" to "dungeon_end")) }
        register<WorldChangeEvent>(EventPriority.HIGHEST) { if (LocationUtils.inDungeon) send(mapOf("type" to "reset")) }

        register<MessageSentEvent>(EventPriority.LOWEST) {
            if (! WebSocket.isConnected()) return@register
            if (! mutedPartyChat.value || ! LocationUtils.inDungeon) return@register
            ThreadUtils.setTimeout(5000) { if (pendingChatMessage == event.message) pendingChatMessage = null }
            pendingChatMessage = event.message
        }

        register<ChatMessageEvent> {
            if (! mutedPartyChat.value) return@register
            if (! event.unformattedText.startsWith("You are currently muted")) return@register
            if (! LocationUtils.inDungeon) return@register
            val pending = pendingChatMessage ?: return@register
            event.isCanceled = true
            pendingChatMessage = null

            val prefix = partyChatPrefixes.find { pending.startsWith(it, ignoreCase = true) }
            sendChat(if (prefix == null) pending else pending.drop(prefix.length))
        }
    }

    override fun CommandBuilder.command() {
        setName("ws")

        literal("users") {
            runs {
                send(mapOf("type" to "check_users"))
            }
        }

        literal("chat") {
            argument("message", StringArgumentType.greedyString()) {
                runs {
                    sendChat(StringArgumentType.getString(it, "message"))
                }
            }

            runs {
                ChatUtils.modMessage("/ws chat <message>")
            }
        }
    }

    private fun sendChat(message: String) {
        val packet = S2CPacketChat("§d${Marceline.mc.user.name}: §r${message.addColor()}")
        send(packet.apply(S2CPacketChat::handle))
    }

    fun sendDungeonInfo() = ThreadUtils.scheduledTaskServer(30) ws@{
        if (DungeonListener.dungeonTeammatesNoSelf.isEmpty()) return@ws
        val serverId = LocrawListener.server.ifEmpty { LocationUtils.serverId } ?: return@ws
        val floor = LocationUtils.dungeonFloor ?: return@ws
        val team = DungeonListener.dungeonTeammates.map { it.name }.ifEmpty { return@ws }
        val entrance = (DungeonScanner.dungeonList.find { (it as? RoomTile)?.data?.type == RoomType.ENTRANCE } as? RoomTile)?.getGridPos() ?: return@ws

        send(C2SPacketDungeonStart(serverId, floor, team, entrance.run { second to first }))
    }
}