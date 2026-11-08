package dev.riftwalk.utils

import dev.riftwalk.Riftwalk
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Posts to a Discord webhook (a channel's Integrations > Webhooks URL). Off the game thread; failures are
 * logged, never thrown. Only discord.com / discordapp.com webhook URLs are accepted.
 */
object DiscordWebhook {
    private val client by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build() }
    private val valid = Regex("^https://(?:ptb\\.|canary\\.)?discord(?:app)?\\.com/api/webhooks/\\d+/[\\w-]+$")

    fun isValid(url: String) = valid.matches(url.trim())

    /** One embed: a title, a coloured bar, a description and optional fields (name to value). */
    fun send(url: String, title: String, description: String, color: Int, fields: List<Pair<String, String>> = emptyList(), onDone: (Boolean, String) -> Unit = { _, _ -> }) {
        val u = url.trim()
        if (! isValid(u)) return onDone(false, "not a Discord webhook URL")
        val json = buildString {
            append("{\"username\":\"Riftwalk\",\"embeds\":[{")
            append("\"title\":").append(q(title.take(250))).append(',')
            append("\"description\":").append(q(description.take(3900))).append(',')
            append("\"color\":").append(color)
            if (fields.isNotEmpty()) {
                append(",\"fields\":[")
                append(fields.take(20).joinToString(",") { (n, v) -> "{\"name\":${q(n.take(250))},\"value\":${q(v.take(1000).ifBlank { "-" })},\"inline\":true}" })
                append(']')
            }
            append(",\"timestamp\":").append(q(java.time.Instant.now().toString()))
            append("}]}")
        }
        val req = HttpRequest.newBuilder(URI.create(u)).timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build()
        client.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete { res, err ->
            val ok = err == null && res.statusCode() in 200 .. 299
            val why = err?.message ?: "HTTP ${res?.statusCode()} ${res?.body()?.take(120) ?: ""}"
            if (! ok) Riftwalk.logger.warn("Discord webhook failed: $why")
            Riftwalk.mc.execute { onDone(ok, if (ok) "sent" else why) }
        }
    }

    /** Uploads files (name to bytes) with a short message. Discord takes up to ~10 MB per message. */
    fun sendFiles(url: String, message: String, files: List<Pair<String, ByteArray>>, onDone: (Boolean, String) -> Unit = { _, _ -> }) {
        val u = url.trim()
        if (! isValid(u)) return onDone(false, "not a Discord webhook URL")
        val boundary = "----riftwalk" + System.nanoTime()
        val out = java.io.ByteArrayOutputStream()
        fun w(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
        w("--$boundary\r\nContent-Disposition: form-data; name=\"payload_json\"\r\nContent-Type: application/json\r\n\r\n")
        w("{\"username\":\"Riftwalk\",\"content\":${q(message.take(1900))}}\r\n")
        files.forEachIndexed { i, (name, bytes) ->
            w("--$boundary\r\nContent-Disposition: form-data; name=\"files[$i]\"; filename=\"$name\"\r\nContent-Type: text/plain\r\n\r\n")
            out.write(bytes)
            w("\r\n")
        }
        w("--$boundary--\r\n")
        val req = HttpRequest.newBuilder(URI.create(u)).timeout(Duration.ofSeconds(30))
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build()
        client.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete { res, err ->
            val ok = err == null && res.statusCode() in 200 .. 299
            val why = err?.message ?: "HTTP ${res?.statusCode()} ${res?.body()?.take(120) ?: ""}"
            if (! ok) Riftwalk.logger.warn("Discord upload failed: $why")
            Riftwalk.mc.execute { onDone(ok, if (ok) "sent" else why) }
        }
    }

    private fun q(s: String) = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> {}
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }
}
