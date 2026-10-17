package dev.spinet.settings

import com.russhwolf.settings.ExperimentalSettingsImplementation
import com.russhwolf.settings.PropertiesSettings
import com.russhwolf.settings.Settings
import java.io.File
import java.util.Properties

private fun portableDataDir(): File? {
    return try {
        val exe = ProcessHandle.current().info().command().orElse(null) ?: return null

        var dir: File? = File(exe).parentFile
        var hops = 0
        while (dir != null && hops < 4) {
            if (File(dir, "portable.txt").exists()) {
                return File(dir, "data").apply { if (!exists()) mkdirs() }
            }
            dir = dir.parentFile
            hops++
        }
        null
    } catch (e: Exception) {
        null
    }
}

private fun homeDataDir(): File {
    val dir = File(System.getProperty("user.home"), ".spinet")
    if (!dir.exists()) dir.mkdirs()
    return dir
}

private val settingsFile: File by lazy {
    val portable = portableDataDir()
    if (portable != null) {
        File(portable, "settings.properties")
    } else {
        File(homeDataDir(), "settings.properties")
    }
}

internal fun appDataDir(): File = settingsFile.parentFile

internal val isPortableInstall: Boolean by lazy { portableDataDir() != null }

private val loadedProperties: Properties by lazy {
    Properties().apply {
        if (settingsFile.exists()) {
            settingsFile.inputStream().use { load(it) }
        }
    }
}

@OptIn(ExperimentalSettingsImplementation::class)
private val sharedSettings: Settings by lazy {
    PropertiesSettings(loadedProperties) { props ->
        settingsFile.outputStream().use { props.store(it, "Spinet settings") }
    }
}

actual fun createAppSettings(): Settings = sharedSettings
