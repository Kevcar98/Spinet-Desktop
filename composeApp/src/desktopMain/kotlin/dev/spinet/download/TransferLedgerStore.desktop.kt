package dev.spinet.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.spinet.settings.SettingsRepository
import dev.spinet.settings.appDataDir
import java.io.File

private const val DOWNLOAD_SIDECAR = ".downloaded.json"

private const val UPLOAD_LOG = "upload-history.json"

internal fun musicRootDir(): File {
    val chosen = SettingsRepository().localFolderUri
    if (chosen.isNotBlank()) {
        val dir = File(chosen)
        if (!dir.isDirectory) dir.mkdirs()
        return dir
    }
    val music = File(System.getProperty("user.home"), "Music")
    return if (music.isDirectory || music.mkdirs()) music else File(System.getProperty("user.home"))
}

private fun sidecarFile(subfolder: String): File {
    val root = musicRootDir()
    val sub = subfolder.trim()
    val dir = if (sub.isBlank()) root else File(root, sanitizeFolder(sub))
    return File(dir, DOWNLOAD_SIDECAR)
}

private fun sanitizeFolder(name: String): String =
    name.replace(Regex("[^A-Za-z0-9-_ ]"), "_").trim().ifEmpty { "track" }

actual suspend fun readDownloadLedgerFile(subfolder: String): String? =
    withContext(Dispatchers.IO) {
        val file = sidecarFile(subfolder)
        if (!file.isFile) null else runCatching { file.readText() }.getOrNull()
    }

actual suspend fun writeDownloadLedgerFile(subfolder: String, json: String): Boolean =
    withContext(Dispatchers.IO) {
        val file = sidecarFile(subfolder)
        val dir = file.parentFile ?: return@withContext false
        if (!dir.isDirectory && !dir.mkdirs()) return@withContext false
        val path = file.toPath()

        if (file.exists()) {
            runCatching { java.nio.file.Files.setAttribute(path, "dos:hidden", false) }
        }
        runCatching {
            file.writeText(json)

            runCatching { java.nio.file.Files.setAttribute(path, "dos:hidden", true) }
            true
        }.getOrDefault(false)
    }

actual suspend fun readUploadLedgerFile(): String? = withContext(Dispatchers.IO) {
    val file = File(appDataDir(), UPLOAD_LOG)
    if (!file.isFile) null else runCatching { file.readText() }.getOrNull()
}

actual suspend fun writeUploadLedgerFile(json: String): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        File(appDataDir(), UPLOAD_LOG).writeText(json)
        true
    }.getOrDefault(false)
}

actual fun ledgerLocationLabel(): String =
    "Downloads: $DOWNLOAD_SIDECAR in each folder under ${musicRootDir().absolutePath}\n" +
        "Uploads: ${File(appDataDir(), UPLOAD_LOG).absolutePath}"
