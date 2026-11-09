package dev.marceline.websocket

import dev.marceline.Marceline
import dev.marceline.Marceline.logger
import dev.marceline.Marceline.mc
import dev.marceline.event.EventBus
import dev.marceline.event.EventBus.register
import dev.marceline.event.impl.GameStartEvent
import dev.marceline.event.impl.WebSocketEvent
import dev.marceline.init.types.ISelfInit
import dev.marceline.utils.ChatUtils
import dev.marceline.utils.GsonUtils
import dev.marceline.utils.ThreadUtils
import dev.marceline.utils.network.ApiAuth
import dev.marceline.utils.network.WebUtils
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.parameter
import io.ktor.websocket.*
import kotlinx.coroutines.*

object WebSocket: ISelfInit {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineName("${Marceline.MOD_NAME}-WebSocket"))
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
        if (! dev.marceline.features.impl.dev.WebSocketFeature.enabled) return
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