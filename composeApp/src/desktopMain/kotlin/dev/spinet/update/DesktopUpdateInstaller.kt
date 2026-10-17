package dev.spinet.update

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.system.exitProcess

object DesktopUpdateInstaller {
    enum class Kind { PORTABLE, INSTALLED }

    val kind: Kind by lazy {
        if (portableRoot() != null) Kind.PORTABLE else Kind.INSTALLED
    }

    fun wants(assetName: String): Boolean {
        val lower = assetName.lowercase()
        return when (kind) {
            Kind.PORTABLE -> lower.endsWith(".zip") && lower.contains("portable")
            Kind.INSTALLED -> lower.endsWith(".msi")
        }
    }

    fun verify(file: File, checksums: String?, assetName: String): Boolean {
        if (checksums.isNullOrBlank()) return true
        val expected = checksums.lineSequence()
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 2)
                if (parts.size == 2) parts[0].lowercase() to parts[1].removePrefix("*") else null
            }
            .firstOrNull { it.second.equals(assetName, ignoreCase = true) }
            ?.first
            ?: return true
        return sha256(file) == expected
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun applyAndRestart(downloaded: File): Boolean = runCatching {
        when (kind) {
            Kind.INSTALLED -> {
                val exe = System.getProperty("jpackage.app-path")
                val relaunch = if (exe.isNullOrBlank()) "" else " & start \"\" \"$exe\""

                ProcessBuilder(
                    "cmd", "/c",
                    "ping -n 4 127.0.0.1 >nul" +
                        " & msiexec /i \"${downloaded.absolutePath}\" /passive /norestart" +
                        relaunch
                ).start()
                exitProcess(0)
            }

            Kind.PORTABLE -> {
                val root = portableRoot() ?: return false
                val staged = File(root, "app.new")
                staged.deleteRecursively()
                if (!unzipAppFolder(downloaded, staged)) return false
                val script = writeSwapScript(root, staged)
                ProcessBuilder("cmd", "/c", "start", "/min", "", script.absolutePath)
                    .directory(root)
                    .start()
                exitProcess(0)
            }
        }
        true
    }.getOrDefault(false)

    private fun portableRoot(): File? = runCatching {
        var dir: File? = File(
            DesktopUpdateInstaller::class.java.protectionDomain.codeSource.location.toURI()
        ).parentFile
        repeat(6) {
            val candidate = dir ?: return@runCatching null
            if (File(candidate, "portable.txt").isFile) return@runCatching candidate
            dir = candidate.parentFile
        }
        null
    }.getOrNull()

    private fun unzipAppFolder(zip: File, target: File): Boolean = runCatching {
        var found = false
        ZipFile(zip).use { archive ->
            val entries = archive.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name.replace('\\', '/')
                if (!name.startsWith("app/")) continue
                val relative = name.removePrefix("app/")
                if (relative.isBlank()) continue
                val out = File(target, relative)

                if (!out.canonicalPath.startsWith(target.canonicalPath + File.separator)) {
                    return@runCatching false
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    archive.getInputStream(entry).use { input ->
                        out.outputStream().use { input.copyTo(it) }
                    }
                    found = true
                }
            }
        }
        found
    }.getOrDefault(false)

    private fun writeSwapScript(root: File, staged: File): File {
        val script = File(root, "update.cmd")
        val exe = File(File(root, "app"), "Spinet.exe").absolutePath
        script.writeText(
            buildString {
                appendLine("@echo off")
                appendLine("setlocal")

                appendLine("ping -n 4 127.0.0.1 >nul")
                appendLine("if exist \"${File(root, "app.old").absolutePath}\" rmdir /s /q \"${File(root, "app.old").absolutePath}\"")
                appendLine("move \"${File(root, "app").absolutePath}\" \"${File(root, "app.old").absolutePath}\" >nul")
                appendLine("move \"${staged.absolutePath}\" \"${File(root, "app").absolutePath}\" >nul")
                appendLine("if errorlevel 1 move \"${File(root, "app.old").absolutePath}\" \"${File(root, "app").absolutePath}\" >nul")
                appendLine("rmdir /s /q \"${File(root, "app.old").absolutePath}\" 2>nul")
                appendLine("start \"\" \"$exe\"")
                appendLine("del \"%~f0\"")
            },
            Charsets.US_ASCII
        )
        return script
    }
}
