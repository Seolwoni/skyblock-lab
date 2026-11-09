package dev.marceline.features.impl.dev.cosmetics.badges

import dev.marceline.Marceline
import dev.marceline.Marceline.mc
import dev.marceline.utils.catch
import dev.marceline.utils.network.DataAPI
import dev.marceline.utils.network.WebUtils
import com.mojang.blaze3d.platform.NativeImage
import kotlinx.serialization.Serializable
import java.nio.file.Files
import kotlin.io.encoding.Base64

object BadgeManager {
    const val URL = "${DataAPI.URL}/badges.json"
    @Volatile private var chars = emptyMap<String, Char>()
    fun charOf(id: String) = chars[id]
    const val SEPARATOR = '\uE100'

    suspend fun load() {
        val manifest = WebUtils.getAs<Map<String, BadgeData>>(URL).getOrElse {
            Marceline.logger.error("Failed to fetch $URL", it)
            return
        }
        chars = manifest.mapNotNull { (id, data) -> id to data.codepoint.toChar() }.toMap()
        BadgeText.rebuild()
        val entries = manifest.entries.sortedBy { it.value.codepoint }.ifEmpty { return }

        val cells = entries.mapNotNull { (id, data) ->
            val bytes = Base64.decode(data.image)
            val cell = catch { BadgePack.fitCell(NativeImage.read(bytes)) } ?: run {
                Marceline.logger.warn("[BadgeManager] Could not decode badge image: $id")
                return@mapNotNull null
            }
            data.codepoint.toChar() to cell
        }.ifEmpty { return }

        BadgePack.buildAtlas(cells.map { it.second }).use { atlas ->
            val pngBytes = atlas.readBytes()
            val chars = cells.map { it.first }
            mc.execute { BadgeFontInjector.apply(pngBytes, chars) }
        }
    }

    private fun NativeImage.readBytes(): ByteArray {
        val tmp = Files.createTempFile("noamm-badges", ".png")
        try {
            writeToFile(tmp)
            return Files.readAllBytes(tmp)
        }
        finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Serializable data class BadgeData(val codepoint: Int, val image: String)
}