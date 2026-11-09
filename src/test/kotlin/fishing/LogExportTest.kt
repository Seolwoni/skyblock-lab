package fishing

import dev.marceline.utils.LogExport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogExportTest {
    @Test fun phoneLooksInSharedDownloadFirst() {
        val d = LogExport.downloadDirs(mobile = true, home = "/data/user/0/net.kdt.pojavlaunch/files", externalStorage = "/storage/emulated/0")
        assertEquals("/storage/emulated/0/Download", d.first())
        assertTrue("/sdcard/Download" in d)
        assertEquals(d.size, d.toSet().size, "listed twice")
    }

    @Test fun pcUsesHomeDownloads() {
        assertEquals(listOf("/home/me/Downloads", "/home/me/Download"), LogExport.downloadDirs(mobile = false, home = "/home/me", externalStorage = null))
        assertEquals(emptyList(), LogExport.downloadDirs(mobile = false, home = null, externalStorage = null))
        assertEquals(emptyList(), LogExport.downloadDirs(mobile = false, home = " ", externalStorage = ""))
    }
}
