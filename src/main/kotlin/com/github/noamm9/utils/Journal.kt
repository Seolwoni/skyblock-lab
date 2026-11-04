package com.github.noamm9.utils

import com.github.noamm9.NoammAddons
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * A plain-text diary of what an automatic feature did and why, one file per feature in config/<mod>/journal/.
 * Written off the game thread; each file is cut to its newest half once it passes 2 MB.
 */
class Journal(name: String) {
    private val file = FabricLoader.getInstance().configDir.resolve(NoammAddons.MOD_NAME).resolve("journal").resolve("$name.log")
    private val fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")

    val path: String get() = file.toAbsolutePath().toString()

    fun log(msg: String) {
        val line = "${LocalDateTime.now().format(fmt)}  ${msg.replace(Regex("[§&][0-9a-fk-or]"), "")}\n"
        io.execute {
            runCatching {
                Files.createDirectories(file.parent)
                if (Files.exists(file) && Files.size(file) > 2_000_000) {
                    val keep = Files.readAllLines(file).let { it.subList(it.size / 2, it.size) }
                    Files.write(file, keep)
                }
                Files.writeString(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            }.onFailure { NoammAddons.logger.warn("journal write failed: ${it.message}") }
        }
    }

    companion object {
        private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "riftwalk-journal").apply { isDaemon = true } }
    }
}
