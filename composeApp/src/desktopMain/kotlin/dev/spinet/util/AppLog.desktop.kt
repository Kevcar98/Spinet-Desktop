package dev.spinet.util

import dev.spinet.settings.appDataDir
import java.awt.Desktop
import java.io.File

actual val logButtonLabel: String? = "Open log folder"

actual fun openAppLog() {
    runCatching {
        System.out.flush()
        val log = File(appDataDir(), "spinet.log")
        if (System.getProperty("os.name").orEmpty().lowercase().contains("win") && log.exists()) {
            ProcessBuilder("explorer.exe", "/select,", log.absolutePath).start()
        } else {
            Desktop.getDesktop().open(log.parentFile)
        }
    }
}

actual val saveLogLabel: String? = null

actual fun saveAppLog(): String? = null
