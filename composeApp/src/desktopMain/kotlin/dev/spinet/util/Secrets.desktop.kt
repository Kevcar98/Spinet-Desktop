package dev.spinet.util

import com.sun.jna.platform.win32.Crypt32Util
import dev.spinet.settings.appDataDir
import dev.spinet.settings.isPortableInstall
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val WINDOWS_PROTECTED: Byte = 1
private const val KEY_FILE_AES: Byte = 2

private val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

actual fun encryptSecret(data: ByteArray): ByteArray? = runCatching {
    if (isWindows) {
        byteArrayOf(WINDOWS_PROTECTED) + Crypt32Util.cryptProtectData(data)
    } else {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(localKey(), "AES"), GCMParameterSpec(128, iv))
        byteArrayOf(KEY_FILE_AES) + iv + cipher.doFinal(data)
    }
}.getOrNull()

actual fun decryptSecret(data: ByteArray): ByteArray? = runCatching {
    when (data.first()) {
        WINDOWS_PROTECTED -> Crypt32Util.cryptUnprotectData(data.copyOfRange(1, data.size))
        KEY_FILE_AES -> {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(localKey(), "AES"), GCMParameterSpec(128, data.copyOfRange(1, 13)))
            cipher.doFinal(data.copyOfRange(13, data.size))
        }
        else -> error("unknown format")
    }
}.getOrNull()

private fun localKey(): ByteArray {
    val file = File(appDataDir(), "secret.key")
    if (file.isFile && file.length() == 32L) return file.readBytes()
    val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
    file.writeBytes(key)
    runCatching { Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------")) }
    return key
}

actual fun secretScope(): String? {
    if (!isWindows || !isPortableInstall) return null
    val machine = System.getenv("COMPUTERNAME").orEmpty() + "|" + System.getProperty("user.name").orEmpty()
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(machine.lowercase().toByteArray())
    return digest.take(6).joinToString("") { "%02x".format(it) }
}
