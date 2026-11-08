package dev.riftwalk.websocket

import dev.riftwalk.Riftwalk
import dev.riftwalk.Riftwalk.logger
import dev.riftwalk.Riftwalk.mc
import dev.riftwalk.event.EventBus
import dev.riftwalk.event.EventBus.register
import dev.riftwalk.event.impl.GameStartEvent
import dev.riftwalk.event.impl.WebSocketEvent
import dev.riftwalk.init.types.ISelfInit
import dev.riftwalk.utils.ChatUtils
import dev.riftwalk.utils.GsonUtils
import dev.riftwalk.utils.ThreadUtils
import dev.riftwalk.utils.network.ApiAuth
import dev.riftwalk.utils.network.WebUtils
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.parameter
import io.ktor.websocket.*
import kotlinx.coroutines.*

object WebSocket: ISelfInit {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineName("${Riftwalk.MOD_NAME}-WebSocket"))
    @Volatile private var session: WebSocketSession? = null
    @Volatile private var connecting = false

    override fun init() {
        ThreadUtils.addShutdownHook {
            scope.cancel()
            session?.cancel()
            session = null
        }

        register<GameStartEvent> {
            scope.launch {
                delay(5000)
                connect()
            }
        }
    }

    fun isConnected() = session?.isActive == true

    fun send(packet: Any) = scope.launch {
        val socket = session?.takeIf(WebSocketSession::isActive) ?: return@launch
        val json = GsonUtils.gson.toJsonTree(packet).asJsonObject
        val type = PacketRegistry.getType(packet)
        if (type != null) json.addProperty("type", type)
        socket.send(Frame.Text(json.toString()))
        ChatUtils.debug("ws", "[WS] sending $json")
    }

    suspend fun connect() {
        if (connecting) return
        if (! dev.riftwalk.features.impl.dev.WebSocketFeature.enabled) return
        connecting = true

        try {
            val token = ApiAuth.token ?: return logger.info("[Websocket] no auth token yet, waiting for auth")
            WebUtils.client.webSocket("wss://ws.noamm.org", {
                parameter("name", mc.user.name)
                parameter("token", token)
            }) {
                session = this

                for (frame in incoming) if (frame is Frame.Text) mc.submit {
                    EventBus.post(WebSocketEvent.Payload(frame.readText()))
                }
            }
        }
        catch (e: Throwable) {
            if (e is CancellationException) throw e
            logger.info("[WebSocket] error: ${e.message}")
        }
        finally {
            session = null
            connecting = false
            scope.launch {
                delay(60_000L)
                connect()
            }
        }
    }
}