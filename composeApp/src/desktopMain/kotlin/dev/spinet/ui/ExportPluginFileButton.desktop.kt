package dev.spinet.ui

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun ExportPluginFileButton(content: () -> String) {
    val scope = rememberCoroutineScope()
    OutlinedButton(onClick = {
        val text = content()
        scope.launch {
            withContext(Dispatchers.IO) {
                DesktopFilePickers.chooseSave("music-player-plugins.json", "json")?.writeText(text)
            }
        }
    }) { Text("Export to file") }
}
