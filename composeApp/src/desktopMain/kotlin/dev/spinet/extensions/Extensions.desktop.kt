package dev.spinet.extensions

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import java.io.ByteArrayInputStream
import java.io.File
import java.util.jar.JarInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.spinet.extension.ApiVersion
import dev.spinet.ui.DesktopFilePickers
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import dev.spinet.settings.appDataDir

actual fun scanExtensions(): ExtensionScan {
    val dir = ExtensionLoader.extensionsDir(appDataDir())
    applyPendingUpdates(dir)
    val result = ExtensionLoader.loadAll(appDataDir())
    return ExtensionScan(
        sources = result.loaded.flatMap { it.sources },
        rejected = result.rejected.map { RejectedExtension(it.file.name, it.reason) },
        origins = result.loaded.flatMap { l -> l.sources.mapNotNull { s -> runCatching { s.id }.getOrNull()?.let { it to l.file.name } } }.toMap()
    ).withoutDuplicateIds()
}

actual val extensionPlatform: String? = "desktop"

actual fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

actual suspend fun installExtensionArtifact(id: String, bytes: ByteArray): InstallOutcome =
    writeJar(id, bytes)

private fun writeJar(baseName: String, bytes: ByteArray): InstallOutcome {
    val dir = ExtensionLoader.extensionsDir(appDataDir())
    val name = baseName.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".jar"
    val target = File(dir, name)
    val temp = File(dir, "$name.download")
    temp.writeBytes(bytes)
    return try {
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        InstallOutcome(true, "Installed. Switch it on above.", InstallOutcome.Next.LOAD_NOW)
    } catch (e: Exception) {
        Files.move(temp.toPath(), File(dir, "$name.pending").toPath(), StandardCopyOption.REPLACE_EXISTING)
        InstallOutcome(true, "Downloaded. It replaces the current version when Spinet restarts.", InstallOutcome.Next.RESTART)
    }
}

private fun applyPendingUpdates(dir: File) {
    dir.listFiles { f: File -> f.name.endsWith(REMOVE_MARKER) }?.forEach { marker ->
        val jar = File(dir, marker.name.removeSuffix(REMOVE_MARKER))
        if (!jar.exists() || jar.delete()) marker.delete()
    }
    dir.listFiles { f: File -> f.name.endsWith(".jar.pending") }?.forEach { pending ->
        runCatching {
            Files.move(
                pending.toPath(),
                File(dir, pending.name.removeSuffix(".pending")).toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }
}

@Composable
actual fun PickExtensionFileButton(text: String, onPicked: (files: List<PickedExtensionFile>) -> Unit) {
    val scope = rememberCoroutineScope()
    OutlinedButton(onClick = {
        scope.launch {
            val picked = withContext(Dispatchers.IO) {
                DesktopFilePickers.chooseOpenMany("jar").map { PickedExtensionFile(it.name, it.readBytes()) }
            }
            if (picked.isNotEmpty()) onPicked(picked)
        }
    }) { Text(text) }
}

actual suspend fun installExtensionFile(fileName: String, bytes: ByteArray): InstallOutcome {
    val api = runCatching {
        JarInputStream(ByteArrayInputStream(bytes)).use {
            it.manifest?.mainAttributes?.getValue("Spinet-Extension-Api")?.trim()?.toIntOrNull()
        }
    }.getOrNull() ?: return InstallOutcome(false, "$fileName isn't a Spinet extension.")
    if (api > ApiVersion.CURRENT) {
        return InstallOutcome(false, "$fileName needs a newer version of Spinet (extension api $api).")
    }
    return writeJar(fileName.removeSuffix(".jar"), bytes)
}

@Composable
actual fun OnAppResumed(action: () -> Unit) = Unit

internal const val REMOVE_MARKER = ".remove"

actual suspend fun uninstallExtension(origin: String): InstallOutcome {
    val dir = ExtensionLoader.extensionsDir(appDataDir())
    val jar = File(dir, origin)
    if (jar.parentFile?.canonicalFile != dir.canonicalFile) {
        return InstallOutcome(false, "Not in the extensions folder.")
    }
    if (!jar.exists() || jar.delete()) return InstallOutcome(true, "Removed {name}.", InstallOutcome.Next.LOAD_NOW)
    File(dir, origin + REMOVE_MARKER).createNewFile()
    return InstallOutcome(true, "Removed {name}. Its file is deleted when Spinet restarts.", InstallOutcome.Next.LOAD_NOW)
}
