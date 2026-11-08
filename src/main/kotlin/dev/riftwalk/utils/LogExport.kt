package dev.riftwalk.utils

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Copies log files into the phone's / computer's Downloads folder (Riftwalk sub-folder), where a file manager
 * can reach them; a launcher's game folder often can't be opened on Android.
 */
object LogExport {
    /** Where Downloads may be, best first (pure: no file system access). */
    fun downloadDirs(mobile: Boolean, home: String?, externalStorage: String?): List<String> {
        val out = ArrayList<String>()
        if (mobile) {
            externalStorage?.takeIf { it.isNotBlank() }?.let { out += "$it/Download" }
            out += "/storage/emulated/0/Download"
            out += "/sdcard/Download"
        }
        home?.takeIf { it.isNotBlank() }?.let { out += "$it/Downloads"; out += "$it/Download" }
        return out.distinct()
    }

    /** Copies [files] into the first Downloads folder that works. The folder used, or the error. */
    fun copy(files: List<Path>, mobile: Boolean = Platform.isMobile): Result<Path> = runCatching {
        val dirs = downloadDirs(mobile, System.getProperty("user.home"), System.getenv("EXTERNAL_STORAGE"))
        var last: Throwable? = null
        for (d in dirs) {
            val base = Path.of(d)
            if (! Files.isDirectory(base)) continue
            val target = base.resolve("Riftwalk")
            try {
                Files.createDirectories(target)
                for (f in files) if (Files.isRegularFile(f)) Files.copy(f, target.resolve(f.fileName.toString()), StandardCopyOption.REPLACE_EXISTING)
                return@runCatching target
            } catch (e: Exception) { last = e }
        }
        throw last ?: IllegalStateException("no Downloads folder found (tried ${dirs.joinToString()})")
    }
}
