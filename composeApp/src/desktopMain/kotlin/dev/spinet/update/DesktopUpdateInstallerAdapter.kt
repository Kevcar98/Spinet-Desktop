package dev.spinet.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class DesktopUpdateInstallerAdapter : UpdateInstaller {
    override fun wants(assetName: String): Boolean = DesktopUpdateInstaller.wants(assetName)

    override suspend fun install(
        assetName: String,
        bytes: ByteArray,
        checksums: String?
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val staging = File(System.getProperty("java.io.tmpdir"), "spinet-update")
            staging.mkdirs()

            val attempt = File(staging, System.currentTimeMillis().toString())
            attempt.mkdirs()

            staging.listFiles()
                ?.filter { it != attempt }
                ?.forEach { runCatching { it.deleteRecursively() } }

            val file = File(attempt, assetName)
            file.writeBytes(bytes)

            if (!DesktopUpdateInstaller.verify(file, checksums, assetName)) {
                file.delete()
                return@withContext "The download did not match the published checksum, so it was discarded."
            }

            if (DesktopUpdateInstaller.applyAndRestart(file)) null
            else "Could not start the update. The current version is untouched."
        }.getOrElse { "Could not start the update: ${it.message ?: it::class.simpleName}" }
    }
}
