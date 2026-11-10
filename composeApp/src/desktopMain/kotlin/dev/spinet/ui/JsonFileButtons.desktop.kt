package dev.spinet.ui

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun SaveJsonFileButton(label: String, suggestedName: String, content: () -> String) {
    val scope = rememberCoroutineScope()
    OutlinedButton(onClick = {
        val text = content()
        scope.launch {
            withContext(Dispatchers.IO) {
                DesktopFilePickers.chooseSave(suggestedName, "json")?.writeText(text)
            }
        }
    }) { Text(label) }
}

@Composable
actual fun rememberJsonSaver(): (suggestedName: String, content: String) -> Unit {
    val scope = rememberCoroutineScope()
    return { suggestedName, content ->
        scope.launch {
            withContext(Dispatchers.IO) {
                DesktopFilePickers.chooseSave(suggestedName, "json")?.writeText(content)
            }
        }
    }
}

@Composable
actual fun OpenCsvFileButton(label: String, onFileRead: (name: String, text: String) -> Unit) {
    val scope = rememberCoroutineScope()
    OutlinedButton(onClick = {
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                DesktopFilePickers.chooseOpen("csv")?.let { it.nameWithoutExtension to it.readText() }
            }
            if (file != null) onFileRead(file.first, file.second)
        }
    }) { Text(label) }
}

@Composable
actual fun rememberJsonOpener(onFileRead: (String) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    return {
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                DesktopFilePickers.chooseOpen("json")?.readText()
            }
            if (text != null) onFileRead(text)
        }
    }
}

@Composable
actual fun rememberImagePicker(onPicked: (path: String) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    return {
        scope.launch {
            val copy = withContext(Dispatchers.IO) {
                val picked = DesktopFilePickers.chooseImage() ?: return@withContext null
                val dir = java.io.File(dev.spinet.settings.appDataDir(), "playlist-art").apply { mkdirs() }
                val target = java.io.File(dir, "${java.util.UUID.randomUUID()}.${picked.extension.lowercase()}")
                runCatching { picked.copyTo(target) }.getOrNull()
            }
            if (copy != null) onPicked(copy.absolutePath)
        }
    }
}
