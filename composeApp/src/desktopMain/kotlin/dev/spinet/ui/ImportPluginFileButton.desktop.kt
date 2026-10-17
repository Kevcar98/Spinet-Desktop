package dev.spinet.ui

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun ImportPluginFileButton(onFileRead: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    OutlinedButton(onClick = {
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                DesktopFilePickers.chooseOpen("json")?.readText()
            }
            if (text != null) onFileRead(text)
        }
    }) { Text("Import from file") }
}
