package dev.marceline.utils.network

import dev.marceline.Marceline.MOD_ID
import dev.marceline.Marceline.MOD_VERSION
import dev.marceline.Marceline.logger
import dev.marceline.Marceline.mc
import dev.marceline.event.EventBus.register
import dev.marceline.event.impl.GameStartEvent
import dev.marceline.init.types.ISelfInit
import dev.marceline.interfaces.IAccountProfileKeyPairManager
import dev.marceline.mixin.IMinecraft
import dev.marceline.utils.GsonUtils.decode
import dev.marceline.utils.GsonUtils.encode
import dev.marceline.utils.ThreadUtils.async
import dev.marceline.utils.ThreadUtils.setTimeout
import dev.marceline.utils.network.WebUtils.client
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import net.minecraft.world.entity.player.ProfileKeyPair
import java.nio.ByteBuffer
import java.security.PrivateKey
import java.security.Signature
import java.util.*
import kotlin.io.encoding.Base64
import kotlin.jvm.optionals.getOrNull

object ApiAuth: ISelfInit {
    private const val AUTH_URL = "${DataAPI.URL}/hypixel/auth"
    @Volatile private var tokenInfo: TokenResponse? = null
    @Volatile var keyPairUnavailable = false
        private set

    internal val token get() = tokenInfo?.token

    @Volatile private var started = false

    /**
     * No sign-in at game start any more: the upstream data server (api.noamm.org) is only contacted
     * when something actually needs it (a stats lookup, or the WebSocket feature when it's on).
     */
    override fun init() {
        register<GameStartEvent> {
            if (dev.marceline.features.impl.dev.WebSocketFeature.enabled) requestToken()
        }
    }

    /** Starts signing in once; later calls do nothing while it runs or after it worked. */
    fun requestToken() {
        if (started) return
        started = true
        async(::updateToken)
    }

    private suspend fun updateToken(): Unit = try {
        val resolved = getProfileKeyPair() ?: return run {
            logger.error("[ApiAuth] No key pair available.")
            keyPairUnavailable = true
            setTimeout(5 * 60 * 1000L, ::updateToken)
        }

        keyPairUnavailable = false

        val signedData = signRandomData(resolved.privateKey) ?: return run {
            logger.error("[ApiAuth] Failed to sign random data, retrying in 5 minutes")
            setTimeout(5 * 60 * 1000L, ::updateToken)
        }

        val request = TokenRequest(
            KeyPairInfo(
                mc.user.profileId.toString(),
                Base64.encode(resolved.publicKey.data.key.encoded),
                Base64.encode(resolved.publicKey.data.keySignature),
                resolved.publicKey.data.expiresAt.toEpochMilli()
            ),
            signedData, MOD_ID, "@MINECRAFT_VERSION@", MOD_VERSION
        )

        val response = client.post(AUTH_URL) {
            contentType(ContentType.Application.Json)
            setBody(encode(request))
        }

        if (! response.status.isSuccess()) return run {
            val error = response.bodyAsText()
            logger.error("[ApiAuth] Auth failed (${response.status}): $error, retrying in 15 minutes")
            setTimeout(15 * 60 * 1000L, ::updateToken)
        }

        val tokenResponse = decode<TokenResponse>(response.bodyAsText()).also { tokenInfo = it }
        val refreshAtMillis = (tokenResponse.expiresAt - tokenResponse.issuedAt) - 5 * 60 * 1000

        logger.info("[ApiAuth] Successfully authenticated, refreshing in ${refreshAtMillis / 1000}s")
        setTimeout(refreshAtMillis, ::updateToken)
    }
    catch (e: Exception) {
        logger.error("[ApiAuth] Unexpected error during auth, retrying in 15 minutes", e)
        setTimeout(15 * 60 * 1000L, ::updateToken)
    }

    private suspend fun getProfileKeyPair(): ProfileKeyPair? = withContext(Dispatchers.IO) {
        val profileKeyManager = (mc as IMinecraft).keyPair
        val playerKeypairOpt = profileKeyManager.prepareKeyPair().await()

        if (playerKeypairOpt.isPresent) {
            val data = playerKeypairOpt.get().publicKey().data()
            if (! data.hasExpired()) return@withContext playerKeypairOpt.get()

            logger.warn("[ApiAuth] Vanilla key pair present but expired, falling back to direct Mojang fetch")
        }
        else logger.warn("[ApiAuth] Vanilla returned no key pair, falling back to direct Mojang fetch")

        return@withContext (profileKeyManager as? IAccountProfileKeyPairManager)?.fetchKeyPair()?.getOrNull()
    }

    private fun signRandomData(privateKey: PrivateKey): SignedData? {
        return try {
            val sig = Signature.getInstance("SHA256withRSA")
            val uuid = UUID.randomUUID()
            val buf = ByteBuffer.allocate(16)
                .putLong(uuid.mostSignificantBits)
                .putLong(uuid.leastSignificantBits)

            sig.initSign(privateKey)
            sig.update(buf.array())
            val signed = sig.sign()

            SignedData(Base64.encode(buf.array()), Base64.encode(signed))
        }
        catch (e: Exception) {
            logger.error("[ApiAuth] Failed to sign random data", e)
            null
        }
    }

    private data class TokenRequest(val keyPair: KeyPairInfo, val signedData: SignedData, val mod: String, val minecraftVersion: String, val modVersion: String)
    private data class KeyPairInfo(val uuid: String, val publicKey: String, val publicKeySignature: String, val expiresAt: Long)
    private data class SignedData(val original: String, val signed: String)
    private data class TokenResponse(val token: String, val issuedAt: Long, val expiresAt: Long)
}