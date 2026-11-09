package dev.marceline.utils

import dev.marceline.Marceline
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * A plain-text diary of what the automatic features did and why.
 * One file per game run (config/<mod>/journal/run_<start>.log): every Journal writes into it, each line tagged
 * with its name. The newest [KEEP_RUNS] runs are kept. Written off the game thread.
 */
class Journal(private val name: String) {
    val path: String get() = runFile.toAbsolutePath().toString()

    fun log(msg: String) {
        val line = "${LocalDateTime.now().format(fmt)}  [$name] ${msg.replace(Regex("[§&][0-9a-fk-or]"), "")}\n"
        io.execute {
            runCatching {
                Files.createDirectories(runFile.parent)
                if (Files.exists(runFile) && Files.size(runFile) > MAX_BYTES) {
                    val keep = Files.readAllLines(runFile).let { it.subList(it.size / 2, it.size) }
                    Files.write(runFile, keep)
                }
                Files.writeString(runFile, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            }.onFailure { Marceline.logger.warn("journal write failed: ${it.message}") }
        }
    }

    companion object {
        private const val KEEP_RUNS = 10
        private const val MAX_BYTES = 20_000_000L
        private val fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
        private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "marceline-journal").apply { isDaemon = true } }

        val dir: Path = FabricLoader.getInstance().configDir.resolve(Marceline.MOD_NAME).resolve("journal")

        /** This game's log; named when the game started. */
        val runFile: Path = dir.resolve("run_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".log")

        /** Run logs, newest first (this run included once it has a line in it). */
        fun runs(): List<Path> = runCatching {
            Files.list(dir).use { st -> st.filter { it.fileName.toString().let { n -> n.startsWith("run_") && n.endsWith(".log") } }.sorted(Comparator.reverseOrder()).toList() }
        }.getOrDefault(emptyList())

        init {
            io.execute { runCatching { runs().filter { it != runFile }.drop(KEEP_RUNS - 1).forEach { Files.deleteIfExists(it) } } }
        }
    }
}
