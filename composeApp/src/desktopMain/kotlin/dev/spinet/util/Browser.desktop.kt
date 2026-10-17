package dev.spinet.util

import java.awt.Desktop
import java.net.URI

actual fun openUrlExternally(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
        }
    }
}
