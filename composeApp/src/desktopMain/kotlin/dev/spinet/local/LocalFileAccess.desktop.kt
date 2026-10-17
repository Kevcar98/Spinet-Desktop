package dev.spinet.local

import java.io.File
import java.net.URI

actual fun isLocalFileReadable(streamUrl: String): Boolean = when {
    streamUrl.isBlank() -> false

    streamUrl.startsWith("content://") -> false
    else -> runCatching {
        val file = if (streamUrl.startsWith("file:")) File(URI(streamUrl)) else File(streamUrl)
        file.canRead()
    }.getOrDefault(false)
}
